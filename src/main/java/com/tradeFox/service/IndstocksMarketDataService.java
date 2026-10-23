package com.tradeFox.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IndstocksMarketDataService {
    private static final Logger log = LoggerFactory.getLogger(IndstocksMarketDataService.class);
    private static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofHours(24);
    private static final Duration TOKEN_REFRESH_BUFFER = Duration.ofMinutes(5);
    private static final Duration TOKEN_RETRY_DELAY = Duration.ofMinutes(5);
    private static final Map<String, Duration> HISTORICAL_MAX_RANGES = Map.ofEntries(
            Map.entry("1minute", Duration.ofDays(7)),
            Map.entry("2minute", Duration.ofDays(7)),
            Map.entry("3minute", Duration.ofDays(7)),
            Map.entry("4minute", Duration.ofDays(7)),
            Map.entry("5minute", Duration.ofDays(7)),
            Map.entry("10minute", Duration.ofDays(7)),
            Map.entry("15minute", Duration.ofDays(7)),
            Map.entry("30minute", Duration.ofDays(7)),
            Map.entry("60minute", Duration.ofDays(15)),
            Map.entry("120minute", Duration.ofDays(15)),
            Map.entry("180minute", Duration.ofDays(15)),
            Map.entry("240minute", Duration.ofDays(15)),
            Map.entry("1day", Duration.ofDays(365)),
            Map.entry("1week", Duration.ofDays(365)),
            Map.entry("1month", Duration.ofDays(365)));
    private static final List<HistoricalInterval> HISTORICAL_INTERVALS = List.of(
            new HistoricalInterval("1minute", "1 minute", 7),
            new HistoricalInterval("2minute", "2 minutes", 7),
            new HistoricalInterval("3minute", "3 minutes", 7),
            new HistoricalInterval("4minute", "4 minutes", 7),
            new HistoricalInterval("5minute", "5 minutes", 7),
            new HistoricalInterval("10minute", "10 minutes", 7),
            new HistoricalInterval("15minute", "15 minutes", 7),
            new HistoricalInterval("30minute", "30 minutes", 7),
            new HistoricalInterval("60minute", "1 hour", 15),
            new HistoricalInterval("120minute", "2 hours", 15),
            new HistoricalInterval("180minute", "3 hours", 15),
            new HistoricalInterval("240minute", "4 hours", 15),
            new HistoricalInterval("1day", "1 day", 365),
            new HistoricalInterval("1week", "1 week", 365),
            new HistoricalInterval("1month", "1 month", 365));

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String apiBaseUrl;
    private final String clientId;
    private final String mpin;
    private final String totpSecret;
    private final String scripCodes;
    private final List<String> websocketInstruments;
    private final Map<String, String> websocketInstrumentNames;
    private final Map<String, Object> indexQuotes = new ConcurrentHashMap<>();
    private final Map<String, Deque<IndexPriceSample>> indexHistory = new ConcurrentHashMap<>();
    private final Map<String, CachedStockQuote> stockQuoteCache = new ConcurrentHashMap<>();
    private final Object instrumentMasterLock = new Object();
    private final Object stockQuoteLock = new Object();

    private volatile Map<String, Instrument> instrumentAliases = Map.of();
    private volatile Instant instrumentMasterFetchedAt;

    private volatile String accessToken;
    private volatile Instant tokenExpiresAt;
    private volatile Instant lastTokenGeneratedAt;
    private volatile WebSocket priceSocket;
    private volatile String priceSocketToken;
    private volatile String connectingSocketToken;
    private volatile Instant nextTokenAttempt = Instant.EPOCH;
    private volatile boolean reportedMissingCredentials;
    private volatile MarketSnapshot snapshot = new MarketSnapshot(null, Map.of(), "No market data yet");

    public IndstocksMarketDataService(
            @Value("${indstocks.api-base-url}") String apiBaseUrl,
            @Value("${indstocks.client-id:}") String clientId,
            @Value("${indstocks.mpin:}") String mpin,
            @Value("${indstocks.totp-secret:}") String totpSecret,
            @Value("${indstocks.scrip-codes:}") String scripCodes,
            @Value("${indstocks.websocket-instruments:}") String websocketInstruments) {
        this.apiBaseUrl = apiBaseUrl;
        this.objectMapper = new ObjectMapper();
        this.clientId = clientId.trim();
        this.mpin = mpin.trim();
        this.totpSecret = totpSecret.trim();
        this.scripCodes = normalizeScripCodes(scripCodes);
        this.websocketInstruments = parseWebsocketInstruments(websocketInstruments);
        this.websocketInstrumentNames = createWebsocketInstrumentNames(this.websocketInstruments);
    }

    /** Creates a token at startup, then refreshes shortly before the 24-hour expiry. */
    @Scheduled(fixedDelayString = "${indstocks.token-check-ms:60000}")
    public synchronized void refreshTokenIfNeeded() {
        if (clientId.isBlank() || mpin.isBlank() || totpSecret.isBlank()) {
            if (!reportedMissingCredentials) {
                log.info("INDstocks token generation is waiting for INDSTOCKS_CLIENT_ID, INDSTOCKS_MPIN and INDSTOCKS_TOTP_SECRET.");
                reportedMissingCredentials = true;
            }
            return;
        }

        Instant now = Instant.now();
        if (accessToken != null && tokenExpiresAt != null
                && now.isBefore(tokenExpiresAt.minus(TOKEN_REFRESH_BUFFER))) return;
        if (now.isBefore(nextTokenAttempt)) return;
        if (lastTokenGeneratedAt != null
                && now.isBefore(lastTokenGeneratedAt.plusSeconds(60))) {
            nextTokenAttempt = lastTokenGeneratedAt.plusSeconds(60);
            return;
        }

        nextTokenAttempt = now.plus(TOKEN_RETRY_DELAY);
        try {
            String requestBody = objectMapper.writeValueAsString(
                    Map.of("mpin", mpin, "totp", currentTotp(totpSecret)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/generate/token"))
                    .timeout(Duration.ofSeconds(15))
                    .header("x-api-key", clientId)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new IllegalStateException("INDstocks token request failed");
            }
            JsonNode response = objectMapper.readTree(httpResponse.body());

            JsonNode tokenNode = response == null ? null : response.path("token");
            if (tokenNode == null || tokenNode.isMissingNode()) {
                tokenNode = response == null ? null : response.path("data").path("token");
            }
            String token = tokenNode == null || tokenNode.isMissingNode() || tokenNode.isNull()
                    ? null : tokenNode.asText();
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Token response did not contain a token");
            }
            accessToken = token;
            lastTokenGeneratedAt = Instant.now();
            tokenExpiresAt = lastTokenGeneratedAt.plus(ACCESS_TOKEN_LIFETIME);
            nextTokenAttempt = Instant.EPOCH;
            connectPriceSocketIfNeeded(token);
            log.info("INDstocks access token generated; it will refresh before expiry.");
            refreshMarketData();
        } catch (Exception exception) {
            // Do not log response bodies or request data; they can contain account details.
            log.warn("INDstocks token generation failed. Check the Client ID, MPIN, TOTP secret, and server clock.");
        }
    }

    /** Fetches one quote snapshot per backend instance, not once per frontend visitor. */
    @Scheduled(fixedDelayString = "${indstocks.market-refresh-ms:60000}", initialDelay = 5000)
    public void refreshMarketData() {
        String token = accessToken;
        if (token == null || token.isBlank()) return;
        connectPriceSocketIfNeeded(token);
        if (scripCodes.isBlank()) return;

        try {
            String encodedCodes = URLEncoder.encode(scripCodes, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl
                            + "/market/quotes/full?scrip-codes=" + encodedCodes))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", token)
                    .GET()
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                if (httpResponse.statusCode() == 401 || httpResponse.statusCode() == 403) {
                    accessToken = null;
                    tokenExpiresAt = Instant.EPOCH;
                }
                throw new IllegalStateException("INDstocks quote request failed");
            }
            JsonNode response = objectMapper.readTree(httpResponse.body());
            JsonNode data = response == null ? null : response.get("data");
            if (data == null || !data.isObject()) {
                throw new IllegalStateException("Quote response did not contain data");
            }
            Map<String, Object> copy = objectMapper.convertValue(data, new TypeReference<>() { });
            snapshot = new MarketSnapshot(Instant.now(), Collections.unmodifiableMap(copy), null);
        } catch (Exception exception) {
            log.warn("INDstocks market data refresh failed; keeping the last successful snapshot.");
            snapshot = new MarketSnapshot(snapshot.updatedAt(), snapshot.data(), "Market data refresh failed");
            if (accessToken == null) refreshTokenIfNeeded();
        }
    }

    public MarketStatus getMarketStatus() {
        boolean tokenReady = accessToken != null && tokenExpiresAt != null && Instant.now().isBefore(tokenExpiresAt);
        return new MarketStatus(tokenReady, tokenExpiresAt,
                snapshot.updatedAt(), snapshot.data(), Map.copyOf(indexQuotes), snapshot.lastError());
    }

    public List<MarketIndexQuote> getMarketIndices() {
        Map<String, MarketIndexQuote> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : indexQuotes.entrySet()) {
            String name = websocketInstrumentNames.getOrDefault(entry.getKey(), entry.getKey());
            Map<?, ?> rawQuote = entry.getValue() instanceof Map<?, ?> quote ? quote : Map.of();
            BigDecimal livePrice = decimalValueFromObject(rawQuote.get("ltp"));
            if (livePrice == null) continue;

            MarketIndexQuote previous = result.get(name);
            Instant updatedAt = websocketTimestamp(entry.getValue());
            if (previous == null) {
                BigDecimal dayChange = decimalValueFromObject(rawQuote.get("day_change"));
                BigDecimal dayChangePercentage = decimalValueFromObject(rawQuote.get("day_change_percentage"));
                result.put(name, new MarketIndexQuote(indexSymbol(name), name, exchangeForIndex(name),
                        livePrice, dayChange, dayChangePercentage,
                        dayChange == null ? "UNKNOWN" : directionFor(dayChange), updatedAt));
            } else {
                result.put(name, updateIndexPrice(previous, livePrice, updatedAt));
            }
        }
        for (String configuredInstrument : websocketInstruments) {
            String name = websocketInstrumentNames.getOrDefault(configuredInstrument, configuredInstrument);
            result.putIfAbsent(name, new MarketIndexQuote(indexSymbol(name), name,
                    exchangeForIndex(name), null, null, null, "UNKNOWN", null));
        }
        return result.values().stream()
                .sorted(Comparator.comparing(MarketIndexQuote::name))
                .toList();
    }

    public IndexHistory getIndexHistory(String name, long startTime, long endTime) {
        String displayName = indexDisplayNameForQuery(name);
        validateIndexHistoryRange(startTime, endTime);
        requireAccessToken();

        List<IndexPriceSample> samples;
        synchronized (this) {
            Deque<IndexPriceSample> history = indexHistory.get(displayName);
            samples = history == null ? List.of() : history.stream()
                    .filter(sample -> sample.timestamp() >= startTime && sample.timestamp() < endTime)
                    .toList();
        }
        return new IndexHistory(indexSymbol(displayName), displayName, startTime, endTime, samples);
    }

    private String indexDisplayNameForQuery(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Index name is required");
        }
        String key = normalizeName(name);
        if (key.contains("NIFTY")) return "NIFTY 50";
        if (key.contains("SENSEX")) return "SENSEX";
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Index not found");
    }

    private void validateIndexHistoryRange(long startTime, long endTime) {
        if (startTime <= 0 || endTime <= 0 || startTime >= endTime) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "startTime and endTime must be positive, and startTime must be before endTime");
        }
        if (endTime > Instant.now().plusSeconds(60).toEpochMilli()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endTime cannot be in the future");
        }
        if (Duration.ofMillis(endTime - startTime).compareTo(Duration.ofDays(7)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Index history is available for windows of up to 7 days");
        }
    }

    /** Resolves a stock name or symbol and returns a cached full quote. */
    public StockQuote getStockQuote(String stockName) {
        if (stockName == null || stockName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock name is required");
        }
        return getStockQuotes(List.of(stockName)).getFirst();
    }

    public List<StockQuote> getStockQuotes(List<String> stockNames) {
        if (stockNames == null || stockNames.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one stock symbol is required");
        }
        if (stockNames.size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A maximum of 100 stocks can be requested");
        }

        String token = requireAccessToken();
        Map<String, Instrument> instrumentsByCode = new LinkedHashMap<>();
        for (String stockName : stockNames) {
            if (stockName == null || stockName.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock symbols cannot be blank");
            }
            Instrument instrument = findInstrument(stockName, token);
            instrumentsByCode.putIfAbsent(instrument.scripCode(), instrument);
        }

        synchronized (stockQuoteLock) {
            Instant now = Instant.now();
            Map<String, StockQuote> quotesByCode = new HashMap<>();
            List<Instrument> instrumentsToFetch = new ArrayList<>();
            for (Instrument instrument : instrumentsByCode.values()) {
                CachedStockQuote cached = stockQuoteCache.get(instrument.scripCode());
                if (cached != null && now.isBefore(cached.fetchedAt().plusSeconds(60))) {
                    quotesByCode.put(instrument.scripCode(), cached.quote());
                } else {
                    instrumentsToFetch.add(instrument);
                }
            }

            Map<String, StockQuote> freshQuotes = fetchStockQuotes(instrumentsToFetch, token);
            for (Map.Entry<String, StockQuote> entry : freshQuotes.entrySet()) {
                quotesByCode.put(entry.getKey(), entry.getValue());
                stockQuoteCache.put(entry.getKey(), new CachedStockQuote(entry.getValue(), entry.getValue().updatedAt()));
            }
            return instrumentsByCode.keySet().stream().map(quotesByCode::get).toList();
        }
    }

    public List<StockSearchResult> searchStocks(String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search query is required");
        }
        if (limit < 1 || limit > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be between 1 and 50");
        }

        String token = requireAccessToken();
        String search = normalizeName(query);
        return getEquityInstrumentAliases(token).values().stream()
                .distinct()
                .filter(instrument -> matchesInstrument(instrument, search))
                .sorted(Comparator
                        .comparing((Instrument instrument) -> !normalizeName(instrument.tradingSymbol()).startsWith(search))
                        .thenComparing(instrument -> normalizeName(instrument.tradingSymbol()))
                        .thenComparing(instrument -> normalizeName(instrument.symbolName())))
                .limit(limit)
                .map(instrument -> new StockSearchResult(
                        instrument.tradingSymbol(), displayInstrumentName(instrument),
                        instrument.exchange(), instrument.scripCode()))
                .toList();
    }

    public HistoricalSeries getHistoricalData(String name, String interval, long startTime, long endTime) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Instrument name is required");
        }
        String normalizedInterval = interval == null ? "" : interval.trim().toLowerCase(Locale.ROOT);
        Duration maximumRange = HISTORICAL_MAX_RANGES.get(normalizedInterval);
        if (maximumRange == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported historical interval");
        }
        if (startTime <= 0 || endTime <= 0 || startTime >= endTime) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "startTime and endTime must be positive, and startTime must be before endTime");
        }
        if (endTime > Instant.now().plusSeconds(60).toEpochMilli()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endTime cannot be in the future");
        }
        if (Duration.ofMillis(endTime - startTime).compareTo(maximumRange) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Requested date range exceeds the limit for this interval");
        }

        String token = requireAccessToken();
        Instrument instrument = findInstrument(name, token);
        String encodedCode = URLEncoder.encode(instrument.scripCode(), StandardCharsets.UTF_8);
        String path = "/market/historical/" + normalizedInterval
                + "?scrip-codes=" + encodedCode
                + "&start_time=" + startTime
                + "&end_time=" + endTime;
        String body = getProviderResource(path, token);
        try {
            JsonNode response = objectMapper.readTree(body);
            if (!response.path("success").asBoolean(false)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Market data provider returned an invalid candle response");
            }
            JsonNode candleArray = response.path("data").path(instrument.scripCode()).path("candles");
            List<Candle> candles = parseCandles(candleArray);
            return new HistoricalSeries(instrument.tradingSymbol(), displayInstrumentName(instrument), "EQUITY",
                    normalizedInterval, startTime, endTime, candles);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Market data provider returned an invalid candle response");
        }
    }

    public List<HistoricalInterval> getHistoricalIntervals() {
        return HISTORICAL_INTERVALS;
    }

    private Instrument findInstrument(String stockName, String token) {
        String key = normalizeName(stockName);
        if (key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock name is required");
        }

        Instrument instrument = getEquityInstrumentAliases(token).get(key);
        if (instrument == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock not found");
        }
        return instrument;
    }

    private Map<String, Instrument> getEquityInstrumentAliases(String token) {
        Instant fetchedAt = instrumentMasterFetchedAt;
        Map<String, Instrument> aliases = instrumentAliases;
        if (fetchedAt == null || Instant.now().isAfter(fetchedAt.plus(Duration.ofHours(24)))) {
            synchronized (instrumentMasterLock) {
                fetchedAt = instrumentMasterFetchedAt;
                if (fetchedAt == null || Instant.now().isAfter(fetchedAt.plus(Duration.ofHours(24)))) {
                    String csv = getProviderResource("/market/instruments?source=equity", token);
                    aliases = parseInstrumentMaster(csv);
                    instrumentAliases = aliases;
                    instrumentMasterFetchedAt = Instant.now();
                } else {
                    aliases = instrumentAliases;
                }
            }
        }
        return aliases;
    }

    private boolean matchesInstrument(Instrument instrument, String query) {
        return normalizeName(instrument.tradingSymbol()).contains(query)
                || normalizeName(instrument.customSymbol()).contains(query)
                || normalizeName(instrument.symbolName()).contains(query);
    }

    private String displayInstrumentName(Instrument instrument) {
        if (!instrument.customSymbol().isBlank()) return instrument.customSymbol();
        if (!instrument.symbolName().isBlank()) return instrument.symbolName();
        return instrument.tradingSymbol();
    }

    private List<Candle> parseCandles(JsonNode candleArray) {
        if (candleArray == null || candleArray.isMissingNode() || candleArray.isNull()) return List.of();
        if (!candleArray.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Market data provider returned an invalid candle list");
        }

        List<Candle> candles = new ArrayList<>();
        for (JsonNode candle : candleArray) {
            JsonNode timestampNode = candle.get("ts");
            BigDecimal open = decimalValue(candle.get("o"));
            BigDecimal high = decimalValue(candle.get("h"));
            BigDecimal low = decimalValue(candle.get("l"));
            BigDecimal close = decimalValue(candle.get("c"));
            JsonNode volumeNode = candle.get("v");
            if (timestampNode == null || !timestampNode.canConvertToLong()
                    || open == null || high == null || low == null || close == null
                    || volumeNode == null || !volumeNode.canConvertToLong()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Market data provider returned an invalid candle");
            }
            long timestamp = timestampNode.asLong();
            long volume = volumeNode.asLong();
            if (timestamp <= 0 || volume < 0
                    || open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0
                    || high.compareTo(low) < 0 || high.compareTo(open) < 0 || high.compareTo(close) < 0
                    || low.compareTo(open) > 0 || low.compareTo(close) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Market data provider returned an invalid candle");
            }
            candles.add(new Candle(timestamp, open, high, low, close, volume));
        }
        candles.sort(Comparator.comparingLong(Candle::timestamp));
        return List.copyOf(candles);
    }

    private String directionFor(BigDecimal change) {
        if (change == null) return "UNKNOWN";
        if (change.signum() == 0) return "UNCHANGED";
        return change.signum() > 0 ? "UP" : "DOWN";
    }

    private BigDecimal decimalValueFromObject(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Instant websocketTimestamp(Object rawQuote) {
        if (!(rawQuote instanceof Map<?, ?> quote) || !(quote.get("timestamp") instanceof Number timestamp)) {
            return Instant.now();
        }
        long value = timestamp.longValue();
        if (value <= 0) return Instant.now();
        return value >= 100_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
    }

    private MarketIndexQuote updateIndexPrice(MarketIndexQuote previous, BigDecimal price, Instant updatedAt) {
        BigDecimal change = previous.dayChange();
        BigDecimal changePercentage = previous.dayChangePercentage();
        if (previous.dayChange() != null && previous.price() != null) {
            BigDecimal previousClose = previous.price().subtract(previous.dayChange());
            if (previousClose.signum() != 0) {
                change = price.subtract(previousClose);
                changePercentage = change.multiply(BigDecimal.valueOf(100))
                        .divide(previousClose, 4, java.math.RoundingMode.HALF_UP);
            }
        }
        return new MarketIndexQuote(previous.symbol(), previous.name(), previous.exchange(), price,
                change, changePercentage, directionFor(change), updatedAt);
    }

    private String indexSymbol(String name) {
        if (name.toUpperCase(Locale.ROOT).contains("SENSEX")) return "SENSEX";
        if (name.toUpperCase(Locale.ROOT).contains("NIFTY")) return "NIFTY";
        return name;
    }

    private String exchangeForIndex(String name) {
        return name.toUpperCase(Locale.ROOT).contains("SENSEX") ? "BSE" : "NSE";
    }

    private Map<String, StockQuote> fetchStockQuotes(List<Instrument> instruments, String token) {
        if (instruments.isEmpty()) return Map.of();
        String codes = instruments.stream().map(Instrument::scripCode)
                .reduce((first, second) -> first + "," + second).orElse("");
        String encodedCodes = URLEncoder.encode(codes, StandardCharsets.UTF_8);
        String body = getProviderResource("/market/quotes/full?scrip-codes=" + encodedCodes, token);
        try {
            JsonNode quoteData = objectMapper.readTree(body).path("data");
            if (!quoteData.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Market data provider returned an invalid quote response");
            }
            Map<String, StockQuote> result = new HashMap<>();
            for (Instrument instrument : instruments) {
                JsonNode data = quoteData.path(instrument.scripCode());
                BigDecimal price = decimalValue(data.get("live_price"));
                if (data.isMissingNode() || price == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Market data provider returned an invalid quote");
                }
                BigDecimal dayChange = decimalValue(data.get("day_change"));
                BigDecimal dayChangePercentage = decimalValue(data.get("day_change_percentage"));
                Map<String, Object> marketDepth = data.path("market_depth").isObject()
                        ? objectMapper.convertValue(data.path("market_depth"), new TypeReference<>() { })
                        : Map.of();
                StockQuote quote = new StockQuote(instrument.tradingSymbol(), displayInstrumentName(instrument),
                        instrument.exchange(), price, dayChange, dayChangePercentage, directionFor(dayChange),
                        decimalValue(data.get("day_open")), decimalValue(data.get("day_high")),
                        decimalValue(data.get("day_low")), decimalValue(data.get("prev_close")),
                        decimalValue(data.get("52week_high")), decimalValue(data.get("52week_low")),
                        decimalValue(data.get("upper_circuit")), decimalValue(data.get("lower_circuit")),
                        longValue(data.get("volume")), marketDepth, Instant.now());
                result.put(instrument.scripCode(), quote);
            }
            return Map.copyOf(result);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Market data provider returned an invalid quote");
        }
    }

    private Map<String, Instrument> parseInstrumentMaster(String csv) {
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Market data provider returned an empty instrument list");
        }

        Map<String, Integer> columns = new HashMap<>();
        List<String> header = rows.getFirst();
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i).replace("\uFEFF", "").trim().toUpperCase(Locale.ROOT), i);
        }
        int exchangeColumn = columns.getOrDefault("EXCH", -1);
        int idColumn = columns.getOrDefault("SECURITY_ID", -1);
        int tradingSymbolColumn = columns.getOrDefault("TRADING_SYMBOL", -1);
        int customSymbolColumn = columns.getOrDefault("CUSTOM_SYMBOL", -1);
        int symbolNameColumn = columns.getOrDefault("SYMBOL_NAME", -1);
        if (exchangeColumn < 0 || idColumn < 0 || tradingSymbolColumn < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Market data provider returned an unsupported instrument list");
        }

        Map<String, Instrument> result = new HashMap<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            String exchange = csvValue(row, exchangeColumn).toUpperCase(Locale.ROOT);
            String securityId = csvValue(row, idColumn);
            String tradingSymbol = csvValue(row, tradingSymbolColumn);
            if ((!exchange.equals("NSE") && !exchange.equals("BSE"))
                    || securityId.isBlank() || tradingSymbol.isBlank()) {
                continue;
            }

            Instrument instrument = new Instrument(exchange + "_" + securityId,
                    tradingSymbol, csvValue(row, customSymbolColumn), csvValue(row, symbolNameColumn), exchange);
            addInstrumentAlias(result, instrument, tradingSymbol);
            addInstrumentAlias(result, instrument, instrument.customSymbol());
            addInstrumentAlias(result, instrument, instrument.symbolName());
        }
        return Map.copyOf(result);
    }

    private void addInstrumentAlias(Map<String, Instrument> aliases, Instrument instrument, String alias) {
        String key = normalizeName(alias);
        if (key.isBlank()) return;
        Instrument existing = aliases.get(key);
        if (existing == null || (existing.exchange().equals("BSE") && instrument.exchange().equals("NSE"))) {
            aliases.put(key, instrument);
        }
    }

    private List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char character = csv.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                row.add(field.toString());
                field.setLength(0);
            } else if ((character == '\n' || character == '\r') && !quoted) {
                if (character == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                if (row.stream().anyMatch(value -> !value.isBlank())) rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(character);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            if (row.stream().anyMatch(value -> !value.isBlank())) rows.add(row);
        }
        return rows;
    }

    private String csvValue(List<String> row, int column) {
        return column >= 0 && column < row.size() ? row.get(column).trim() : "";
    }

    private String normalizeName(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }

    private BigDecimal decimalValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        try {
            return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText().trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Long longValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode() || !value.canConvertToLong()) return null;
        return value.asLong();
    }

    private String requireAccessToken() {
        String token = accessToken;
        if (token == null || token.isBlank() || tokenExpiresAt == null || !Instant.now().isBefore(tokenExpiresAt)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Market data is not configured or its access token is unavailable");
        }
        return token;
    }

    private String getProviderResource(String path, String token) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", token)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                if (token.equals(accessToken)) {
                    accessToken = null;
                    tokenExpiresAt = Instant.EPOCH;
                }
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Market data access token was rejected");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Market data provider request failed");
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Market data provider request was interrupted");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Market data provider is unavailable");
        }
    }

    private List<String> parseWebsocketInstruments(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of("NIDX:26000", "BIDX:1");
        }
        List<String> instruments = new ArrayList<>();
        for (String value : configured.split(",")) {
            String instrument = value.trim();
            if (!instrument.matches("(NIDX|BIDX):[A-Za-z0-9]+")) {
                throw new IllegalArgumentException("INDSTOCKS_WEBSOCKET_INSTRUMENTS must use NIDX:<id> or BIDX:<id> values");
            }
            instruments.add(instrument);
        }
        return List.copyOf(instruments);
    }

    private Map<String, String> createWebsocketInstrumentNames(List<String> instruments) {
        Map<String, String> names = new HashMap<>();
        for (String instrument : instruments) {
            String name = switch (instrument) {
                case "NIDX:26000" -> "NIFTY 50";
                case "BIDX:1" -> "SENSEX";
                default -> instrument;
            };
            names.put(instrument, name);
        }
        return Map.copyOf(names);
    }

    private String normalizeScripCodes(String configured) {
        if (configured == null || configured.isBlank()) return "";
        List<String> codes = new ArrayList<>();
        for (String value : configured.split(",")) {
            String code = value.trim().toUpperCase(java.util.Locale.ROOT);
            if (!code.matches("(NSE|BSE|NFO|MCX)_[A-Z0-9]+")) {
                throw new IllegalArgumentException("INDSTOCKS_SCRIP_CODES must use NSE_, BSE_, NFO_ or MCX_ instrument codes");
            }
            if (!codes.contains(code)) codes.add(code);
        }
        if (codes.size() > 1000) {
            throw new IllegalArgumentException("INDSTOCKS_SCRIP_CODES supports at most 1000 instruments");
        }
        return String.join(",", codes);
    }

    private synchronized void connectPriceSocketIfNeeded(String token) {
        if (websocketInstruments.isEmpty()) return;
        if (token.equals(priceSocketToken) && priceSocket != null
                && !priceSocket.isInputClosed() && !priceSocket.isOutputClosed()) return;
        if (token.equals(connectingSocketToken)) return;

        WebSocket oldSocket = priceSocket;
        priceSocket = null;
        priceSocketToken = null;
        if (oldSocket != null) {
            try {
                oldSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Refreshing access token");
            } catch (Exception ignored) {
                // The old stream can already be closed; continue with the fresh token.
            }
        }
        connectingSocketToken = token;
        try {
            httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .header("Authorization", token)
                    .buildAsync(URI.create("wss://ws-prices.indstocks.com/api/v1/ws/prices"),
                            new IndexPriceListener())
                    .whenComplete((socket, error) -> {
                        synchronized (this) {
                            connectingSocketToken = null;
                            if (error != null) {
                                log.warn("INDstocks index price stream connection failed.");
                                return;
                            }
                            priceSocket = socket;
                            priceSocketToken = token;
                        }
                    });
        } catch (Exception exception) {
            connectingSocketToken = null;
            log.warn("Could not start the INDstocks index price stream.");
        }
    }

    private final class IndexPriceListener implements WebSocket.Listener {
        private final StringBuilder message = new StringBuilder();
        private boolean reportedFirstMessage;

        @Override
        public void onOpen(WebSocket webSocket) {
            try {
                String subscription = objectMapper.writeValueAsString(Map.of(
                        "action", "subscribe", "mode", "ltp", "instruments", websocketInstruments));
                webSocket.sendText(subscription, true);
                log.info("INDstocks index price stream connected.");
                webSocket.request(1);
            } catch (Exception exception) {
                log.warn("Could not subscribe to INDstocks index prices.");
                webSocket.abort();
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            message.append(data);
            if (last) processMessage();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            message.append(StandardCharsets.UTF_8.decode(data));
            if (last) processMessage();
            webSocket.request(1);
            return null;
        }

        private void processMessage() {
            try {
                JsonNode parsed = objectMapper.readTree(message.toString());
                if (parsed != null && parsed.isTextual()) {
                    parsed = objectMapper.readTree(parsed.asText());
                }
                if (!reportedFirstMessage && parsed != null) {
                    log.info("INDstocks price stream message received (mode={}, instrumentPresent={}, ltpPresent={}).",
                            parsed.path("mode").asText("unknown"),
                            parsed.hasNonNull("instrument"),
                            parsed.path("data").hasNonNull("ltp")
                                    || parsed.path("data").hasNonNull("live_price"));
                    reportedFirstMessage = true;
                }
                updateIndexQuote(parsed);
            } catch (Exception exception) {
                log.debug("Ignored an unreadable INDstocks price-stream message.");
            } finally {
                message.setLength(0);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            synchronized (IndstocksMarketDataService.this) {
                if (priceSocket == webSocket) {
                    priceSocket = null;
                    priceSocketToken = null;
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            synchronized (IndstocksMarketDataService.this) {
                if (priceSocket == webSocket) {
                    priceSocket = null;
                    priceSocketToken = null;
                }
            }
            log.warn("INDstocks index price stream disconnected.");
        }
    }

    private void updateIndexQuote(JsonNode message) {
        JsonNode data = message.get("data");
        JsonNode instrument = message.get("instrument");
        if (data == null || instrument == null) return;
        JsonNode ltpNode = data.hasNonNull("ltp") ? data.get("ltp") : data.get("live_price");
        if (ltpNode == null || ltpNode.isNull()) return;
        String instrumentId = instrument.asText();
        int separator = instrumentId.lastIndexOf(':');
        if (separator >= 0) instrumentId = instrumentId.substring(separator + 1);
        for (String configuredInstrument : websocketInstruments) {
            if (configuredInstrument.substring(configuredInstrument.indexOf(':') + 1).equals(instrumentId)) {
                Map<String, Object> quote = new HashMap<>();
                quote.put("ltp", objectMapper.convertValue(ltpNode, Object.class));
                quote.put("timestamp", message.path("timestamp").asLong());
                copyOptionalNumber(data, quote, "day_change");
                copyOptionalNumber(data, quote, "day_change_percentage");
                indexQuotes.put(configuredInstrument, Map.copyOf(quote));
                String displayName = websocketInstrumentNames.getOrDefault(configuredInstrument, configuredInstrument);
                BigDecimal price = decimalValueFromObject(quote.get("ltp"));
                if (price != null) {
                    recordIndexSample(displayName, websocketTimestamp(quote).toEpochMilli(), price);
                }
            }
        }
    }

    private synchronized void recordIndexSample(String name, long timestamp, BigDecimal price) {
        if (timestamp <= 0) timestamp = Instant.now().toEpochMilli();
        Deque<IndexPriceSample> history = indexHistory.computeIfAbsent(name, ignored -> new ArrayDeque<>());
        IndexPriceSample previous = history.peekLast();
        if (previous != null && timestamp < previous.timestamp()) return;
        if (previous != null && previous.timestamp() / 60_000 == timestamp / 60_000) {
            history.removeLast();
        }
        history.addLast(new IndexPriceSample(timestamp, price));
        long cutoff = Instant.now().minus(Duration.ofDays(7)).toEpochMilli();
        while (!history.isEmpty() && history.peekFirst().timestamp() < cutoff) {
            history.removeFirst();
        }
    }

    private void copyOptionalNumber(JsonNode source, Map<String, Object> target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isNumber()) {
            target.put(field, value.numberValue());
        }
    }

    private String currentTotp(String base32Secret) throws Exception {
        byte[] secret = decodeBase32(base32Secret);
        long counter = Instant.now().getEpochSecond() / 30;
        byte[] counterBytes = ByteBuffer.allocate(Long.BYTES).putLong(counter).array();

        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret, "HmacSHA1"));
        byte[] hash = mac.doFinal(counterBytes);
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
    }

    private byte[] decodeBase32(String value) {
        String normalized = value.replaceAll("[\\s=]", "").toUpperCase(java.util.Locale.ROOT);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsInBuffer = 0;
        for (char character : normalized.toCharArray()) {
            int digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(character);
            if (digit < 0) throw new IllegalArgumentException("TOTP secret is not valid Base32");
            buffer = (buffer << 5) | digit;
            bitsInBuffer += 5;
            if (bitsInBuffer >= 8) {
                output.write((buffer >> (bitsInBuffer - 8)) & 0xff);
                bitsInBuffer -= 8;
                buffer &= (1 << bitsInBuffer) - 1;
            }
        }
        byte[] decoded = output.toByteArray();
        if (decoded.length == 0) throw new IllegalArgumentException("TOTP secret is empty");
        return decoded;
    }

    public record MarketSnapshot(Instant updatedAt, Map<String, Object> data, String lastError) { }
    public record MarketStatus(boolean tokenReady, Instant tokenExpiresAt,
                               Instant updatedAt, Map<String, Object> data,
                               Map<String, Object> indices, String lastError) { }
    public record StockQuote(String stockSymbol, String stockName, String exchange, BigDecimal price,
                             BigDecimal dayChange, BigDecimal dayChangePercentage,
                             String direction, BigDecimal dayOpen, BigDecimal dayHigh,
                             BigDecimal dayLow, BigDecimal previousClose,
                             BigDecimal yearHigh, BigDecimal yearLow,
                             BigDecimal upperCircuit, BigDecimal lowerCircuit,
                             Long volume, Map<String, Object> marketDepth, Instant updatedAt) { }
    public record MarketIndexQuote(String symbol, String name, String exchange, BigDecimal price,
                                   BigDecimal dayChange, BigDecimal dayChangePercentage,
                                   String direction, Instant updatedAt) { }
    public record IndexHistory(String symbol, String name, long startTime, long endTime,
                               List<IndexPriceSample> samples) { }
    public record IndexPriceSample(long timestamp, BigDecimal price) { }
    public record StockSearchResult(String stockSymbol, String stockName, String exchange,
                                    String scripCode) { }
    public record HistoricalInterval(String value, String label, int maximumRangeDays) { }
    public record HistoricalSeries(String symbol, String name, String instrumentType,
                                   String interval, long startTime, long endTime,
                                   List<Candle> candles) { }
    public record Candle(long timestamp, BigDecimal open, BigDecimal high, BigDecimal low,
                         BigDecimal close, long volume) { }
    private record Instrument(String scripCode, String tradingSymbol, String customSymbol,
                              String symbolName, String exchange) { }
    private record CachedStockQuote(StockQuote quote, Instant fetchedAt) { }
}
