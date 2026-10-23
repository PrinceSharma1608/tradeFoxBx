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

- On startup, and then shortly before the 24-hour expiry, the backend creates a token with `/generate/token` using the saved TOTP secret and the current six-digit code. Failed attempts back off for five minutes to avoid repeated TOTP lockouts.
- INDstocks keeps only one TOTP-generated token live. Run one backend instance for this integration; a second instance would invalidate the first instance's token.
- The backend fetches configured REST quotes once per minute and keeps the latest successful response in memory. Index LTPs are updated from one backend price-feed WebSocket.
- The frontend should request `GET /api/market/quotes` from this backend about once per minute. It should never call INDstocks directly or receive the broker credentials.
- The quote cache is in memory, so it starts empty after an application restart. No market data is written to PostgreSQL.

## Application endpoints

These are read-only market-data endpoints. They do not place, modify, or cancel orders and do not create transactions.

- `GET /api/market/quotes` returns the latest configured stock snapshot, token status, and available index stream values.
- `GET /api/market/indices` returns NIFTY 50 and SENSEX cards from the WebSocket feed. Price is `null` until a first quote arrives; change fields are populated only if INDstocks supplies them. If no WebSocket instruments are configured, the backend subscribes to the documented examples `NIDX:26000` and `BIDX:1`.
- `GET /api/market/indices/history?name=NIFTY&startTime=...&endTime=...` returns one live price sample per minute for index charts. It keeps a rolling seven-day cache in memory, starting when the backend receives live ticks; there is no historical backfill and the cache clears on restart.
- `GET /api/market/stocks/search?q=rel&limit=20` searches the cached equity instrument master. `limit` must be between 1 and 50.
- `GET /api/market/stocks?name=RELIANCE` resolves a stock by name or symbol and returns price, day change and percentage, open/high/low, previous close, 52-week high/low, circuit limits, volume, market depth, direction, and update time. Results are cached for 60 seconds.
- `GET /api/market/stocks/quotes?symbols=RELIANCE,TCS` fetches multiple full quotes in one provider request (up to 100 symbols) and uses the same 60-second per-stock cache.
- `GET /api/market/stocks/details?symbol=RELIANCE` returns the same live quote in the detail-page response shape. Market cap, P/E, beta, and sector are `null` because the supplied INDstocks docs do not provide those fundamentals; the response does not substitute made-up values.
- `GET /api/market/stocks/chart?symbol=RELIANCE&range=1D` returns actual historical candles mapped to chart points. Supported ranges are `1D`, `1W`, `1M`, `1Y`, and `ALL` (five years of monthly candles).
- `GET /api/market/history?name=RELIANCE&interval=1day&startTime=...&endTime=...` returns equity chart candles. `startTime` and `endTime` are Unix epoch milliseconds; each returned candle's `timestamp` is Unix epoch seconds. Candles contain open, high, low, close, and volume. The API validates INDstocks' per-request maximum ranges and returns an empty candle list when the provider has no data for that instrument/window.
- `GET /api/market/intervals` returns supported candle intervals and their maximum request windows.
- `GET /api/users/{userId}` returns the active user's ID, name, email, and wallet balance. The `userId` comes from the login response.
- `GET /api/watchlist?userId=1` returns that user's saved symbols with live price, daily change/percentage, exchange, direction, and quote update time. It uses the batched quote request, supports up to 100 saved stocks, and returns an empty list when the watchlist is empty.

The frontend should call these backend endpoints; it must not receive the INDstocks access token or credentials. Refresh the overview and index endpoints about once per minute. Historical candles can be requested when a chart is opened or when its interval/date range changes.

The user-details endpoint currently relies on the supplied ID because authentication and authorization checks have not been added yet.

## INDstocks references

- [TOTP-based token generation and token lifecycle](https://api-docs.indstocks.com/Users/#method-2-totp-based-token-generation)
- [REST market quotes](https://api-docs.indstocks.com/MarketQuote/)
- [WebSocket price feed](https://api-docs.indstocks.com/Websockets/)
- [Instrument master](https://api-docs.indstocks.com/instruments/)

## Local configuration for a clone

Copy `application-local.properties.example` to `application-local.properties` in the project root or configure `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET` in the environment. The local properties file is git-ignored and stays outside the packaged application resources. Provide INDstocks credentials through the environment variables shown above.
