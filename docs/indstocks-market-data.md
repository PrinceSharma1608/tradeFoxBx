# INDstocks read-only market data

The backend uses INDstocks for market data only. It does not call order or transaction endpoints.

## One-time TOTP setup

INDstocks requires TOTP enrollment on its dashboard; its API cannot enable TOTP or retrieve the secret. In the INDstocks dashboard, open **API Trading / Access Tokens**, choose **Setup TOTP**, enroll an authenticator, and finish confirmation before leaving the page. Save the displayed Client ID and the Base32 TOTP secret in a local secret store. The secret is shown only once.

Provide these as environment variables when starting the backend. Do not commit them or put them in `application.properties`:

```powershell
$env:INDSTOCKS_CLIENT_ID = '<Client ID from the dashboard>'
$env:INDSTOCKS_MPIN = '<INDstocks MPIN>'
$env:INDSTOCKS_TOTP_SECRET = '<Base32 secret from TOTP enrollment>'
$env:INDSTOCKS_SCRIP_CODES = 'NSE_2885,NSE_11536'
$env:INDSTOCKS_WEBSOCKET_INSTRUMENTS = 'NIDX:26000,BIDX:1'
```

The two instrument lists above use documentation examples. Confirm the IDs for the exact instruments you want, especially the BSE index, before relying on them. `INDSTOCKS_SCRIP_CODES` is for REST quote instruments (`NSE_`, `BSE_`, `NFO_`, or `MCX_`). `INDSTOCKS_WEBSOCKET_INSTRUMENTS` is for index LTPs and uses `NIDX:` or `BIDX:`.

## Runtime behavior

- The token scheduler stays active at all times, but calls `/generate/token` only during the NSE regular session (09:15–15:30 IST, weekdays, excluding configured holidays). A token is generated at the next session if the backend starts after hours or the prior token expires while the market is closed. Failed attempts back off for five minutes to avoid repeated TOTP lockouts.
- INDstocks keeps only one TOTP-generated token live. Run one backend instance for this integration; a second instance would invalidate the first instance's token.
- Backend endpoints remain available 24/7. The backend fetches configured REST quotes once per minute during the NSE regular session and keeps the latest successful response in memory. It closes the index price-feed WebSocket outside that session. After hours, quote endpoints return cached values when available; they do not call INDstocks.
- Market hours use Asia/Kolkata time, Monday to Friday, 09:15 inclusive to 15:30 exclusive. Configure exchange holidays with `INDSTOCKS_MARKET_HOLIDAYS` as comma-separated `YYYY-MM-DD` dates.
- The frontend should request `GET /api/market/quotes` from this backend about once per minute. It should never call INDstocks directly or receive the broker credentials.
- The quote cache is in memory, so it starts empty after an application restart. No market data is written to PostgreSQL.

## Application endpoints

These are read-only market-data endpoints. They do not place, modify, or cancel orders and do not create transactions.

- `GET /api/market/quotes` returns configured stock quotes keyed by symbol, token status, and available index stream values. Its stock values use the same 60-second quote cache as `/api/market/stocks` and `/api/market/stocks/details`.
- `GET /api/market/indices` returns NIFTY 50 and SENSEX cards from the WebSocket feed. Price is `null` until a first quote arrives; change fields are populated only if INDstocks supplies them. If no WebSocket instruments are configured, the backend subscribes to the documented examples `NIDX:26000` and `BIDX:1`.
- `GET /api/market/indices/history?name=NIFTY&startTime=...&endTime=...` returns one live price sample per minute for index charts. It keeps a rolling seven-day cache in memory, starting when the backend receives live ticks; there is no historical backfill and the cache clears on restart.
- `GET /api/market/stocks/search?q=rel&limit=20` searches the cached equity instrument master. `limit` must be between 1 and 50.
- `GET /api/market/stocks?name=RELIANCE` resolves a stock by name or symbol and returns price, day change and percentage, open/high/low, previous close, 52-week high/low, circuit limits, volume, market depth, direction, and update time. Results are cached for 60 seconds.
- `GET /api/market/stocks/quotes?symbols=RELIANCE,TCS` fetches multiple full quotes in one provider request (up to 100 symbols) and uses the same 60-second per-stock cache.
- `GET /api/market/stocks/details?symbol=RELIANCE` returns the same live quote in the detail-page response shape. Market cap, P/E, beta, and sector are `null` because the supplied INDstocks docs do not provide those fundamentals; the response does not substitute made-up values.
- `GET /api/market/stocks/chart?symbol=RELIANCE&range=1D` returns actual historical candles mapped to chart points. Supported ranges are `1D`, `1W`, `1M`, `1Y`, and `ALL`; `ALL` returns up to one year because that is the provider's maximum monthly-history window.
- `GET /api/market/history?name=RELIANCE&interval=1day&startTime=...&endTime=...` returns equity chart candles. `startTime` and `endTime` are Unix epoch milliseconds; each returned candle's `timestamp` is Unix epoch seconds. Candles contain open, high, low, close, and volume. The API validates INDstocks' per-request maximum ranges and returns an empty candle list when the provider has no data for that instrument/window.
- `GET /api/market/intervals` returns supported candle intervals and their maximum request windows.
- `GET /api/users/{userId}` returns the active user's ID, name, email, and wallet balance. It requires the JWT returned by login and only permits the matching account ID.
- User-specific orders, holdings, and watchlist endpoints also require `Authorization: Bearer <JWT>` and only allow the signed-in account to access its own records.
- `GET /api/watchlist?userId=1` returns that user's saved symbols with live price, daily change/percentage, exchange, direction, and quote update time. It uses the batched quote request, supports up to 100 saved stocks, and returns an empty list when the watchlist is empty.

## Authentication and paper orders

- `POST /api/auth/signup` and `POST /api/auth/login` are public. Successful responses contain a signed, expiring JWT.
- Empty login fields return HTTP 400; invalid credentials return HTTP 401.
- `POST /api/orders` writes only to this application's PostgreSQL tables. It never sends orders to INDstocks. Market orders placed while the exchange is closed and limit orders waiting for their price are kept in `pending_orders`; BUY funds and SELL shares are reserved while they wait. During market hours, a once-per-minute worker reads queued orders, executes eligible paper trades at provider prices, updates holdings and wallet balances, and inserts the transaction. A market BUY that gaps above its reserved reference amount is rejected and refunded.
- Send an `Idempotency-Key` header when creating an order. Repeating the same key and body returns the original order; reusing that key with different order details returns HTTP 409. An immediate identical retry without a key is also absorbed for five seconds.
- Wallet reads are locked during paper order updates, and holdings are unique by `(user_id, stock_symbol)`.

The PostgreSQL repair for existing databases is in `database/portfolio_schema_patch.sql`. Run it once on a database that has the supplied tables before using these endpoints; it creates `pending_orders` and moves existing pending orders into the queue as limit orders.

The frontend should call these backend endpoints; it must not receive the INDstocks access token or credentials. Refresh the overview and index endpoints about once per minute. Historical candles can be requested when a chart is opened or when its interval/date range changes.


## INDstocks references

- [TOTP-based token generation and token lifecycle](https://api-docs.indstocks.com/Users/#method-2-totp-based-token-generation)
- [REST market quotes](https://api-docs.indstocks.com/MarketQuote/)
- [WebSocket price feed](https://api-docs.indstocks.com/Websockets/)
- [Instrument master](https://api-docs.indstocks.com/instruments/)

## Local configuration for a clone

Copy `application-local.properties.example` to `application-local.properties` in the project root or configure `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET` in the environment. The local properties file is git-ignored and stays outside the packaged application resources. Provide INDstocks credentials through the environment variables shown above.
