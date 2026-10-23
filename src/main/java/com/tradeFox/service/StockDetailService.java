package com.tradeFox.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class StockDetailService {
    private static final Logger log = LoggerFactory.getLogger(StockDetailService.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final IndstocksMarketDataService marketDataService;
    private final PortfolioTradingService portfolioTradingService;

    public StockDetailService(IndstocksMarketDataService marketDataService,
                              PortfolioTradingService portfolioTradingService) {
        this.marketDataService = marketDataService;
        this.portfolioTradingService = portfolioTradingService;
    }

    /** Dashboard data comes from the same provider snapshot as the other market endpoints. */
    public Map<String, Object> getLiveMarketQuotes() {
        IndstocksMarketDataService.MarketStatus status = marketDataService.getMarketStatus();
        Map<String, Object> quotesBySymbol = new LinkedHashMap<>();
        List<IndstocksMarketDataService.StockQuote> quotes = List.of();
        if (status.tokenReady() || !marketDataService.isMarketOpen()) {
            try {
                quotes = marketDataService.getConfiguredStockQuotes();
            } catch (ResponseStatusException ignored) {
                // After hours, show an empty snapshot if this process has no cached market data yet.
            }
        }
        for (IndstocksMarketDataService.StockQuote quote : quotes) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", quote.stockSymbol());
            data.put("name", quote.stockName());
            data.put("exchange", quote.exchange());
            data.put("live_price", quote.price());
            data.put("price", quote.price());
            data.put("day_change", quote.dayChange());
            data.put("day_change_percentage", quote.dayChangePercentage());
            data.put("direction", quote.direction());
            data.put("open", quote.dayOpen());
            data.put("high", quote.dayHigh());
            data.put("low", quote.dayLow());
            data.put("prev_close", quote.previousClose());
            data.put("volume", quote.volume());
            quotesBySymbol.put(quote.stockSymbol(), data);
        }
        Map<String, Object> indices = new LinkedHashMap<>();
        for (IndstocksMarketDataService.MarketIndexQuote quote : marketDataService.getMarketIndices()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("lastPrice", quote.price());
            data.put("dayChange", quote.dayChange());
            data.put("dayChangePercentage", quote.dayChangePercentage());
            data.put("direction", quote.direction());
            indices.put(quote.name(), data);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tokenReady", status.tokenReady());
        response.put("tokenExpiresAt", status.tokenExpiresAt());
        response.put("updatedAt", status.updatedAt());
        response.put("data", quotesBySymbol);
        response.put("indices", indices);
        response.put("lastError", status.lastError());
        return response;
    }

    public StockDetails getStockDetails(String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock symbol is required");
        }
        IndstocksMarketDataService.StockQuote quote = marketDataService.getStockQuote(rawSymbol.trim());
        return new StockDetails(
                quote.stockSymbol(), quote.stockName(), quote.exchange(), quote.price(),
                quote.dayChange(), quote.dayChangePercentage(), quote.direction(),
                quote.dayOpen(), quote.previousClose(), quote.dayHigh(), quote.dayLow(),
                quote.yearHigh(), quote.yearLow(), quote.volume(), quote.upperCircuit(),
                quote.lowerCircuit(), null, null, null, null);
    }

    public List<ChartPoint> getChartData(String rawSymbol, String rawRange) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock symbol is required");
        }
        String range = rawRange == null || rawRange.isBlank()
                ? "1D" : rawRange.trim().toUpperCase(Locale.ROOT);
        ChartWindow window = switch (range) {
            case "1D" -> new ChartWindow("5minute", 1, "HH:mm");
            case "1W" -> new ChartWindow("60minute", 7, "dd MMM HH:mm");
            case "1M" -> new ChartWindow("1day", 31, "dd MMM");
            case "1Y" -> new ChartWindow("1week", 365, "dd MMM yyyy");
            case "ALL" -> new ChartWindow("1month", 365, "MMM yyyy");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Range must be one of 1D, 1W, 1M, 1Y, or ALL");
        };

        Instant now = Instant.now();
        long end = now.toEpochMilli();
        long start = now.minusSeconds(window.days() * 24L * 60L * 60L).toEpochMilli();
        IndstocksMarketDataService.HistoricalSeries series = marketDataService.getHistoricalData(
                rawSymbol.trim(), window.interval(), start, end);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(window.labelFormat());
        return series.candles().stream().map(candle -> {
            String label = Instant.ofEpochSecond(candle.timestamp()).atZone(MARKET_ZONE).format(formatter);
            return new ChartPoint(label, candle.close(), candle.open(), candle.high(), candle.low(),
                    candle.close(), candle.volume());
        }).toList();
    }

    /** Reprices pending paper limit orders from INDstocks quotes; no broker order API is called. */
    @Scheduled(fixedDelayString = "${indstocks.market-refresh-ms:60000}", initialDelay = 15000)
    public void executeTriggeredPaperOrders() {
        if (!marketDataService.isMarketOpen()) return;
        List<String> symbols = portfolioTradingService.getPendingStockSymbols();
        if (symbols.isEmpty()) return;
        try {
            Map<String, java.math.BigDecimal> prices = new HashMap<>();
            for (int start = 0; start < symbols.size(); start += 100) {
                int end = Math.min(start + 100, symbols.size());
                for (IndstocksMarketDataService.StockQuote quote
                        : marketDataService.getStockQuotes(symbols.subList(start, end))) {
                    prices.put(quote.stockSymbol().toUpperCase(Locale.ROOT), quote.price());
                }
            }
            for (String symbol : symbols) {
                if (!prices.containsKey(symbol.toUpperCase(Locale.ROOT))) {
                    portfolioTradingService.rejectPendingOrdersForUnsupportedSymbol(symbol);
                }
            }
            portfolioTradingService.checkAndExecutePendingOrders(prices);
        } catch (Exception exception) {
            log.warn("Pending paper orders were not checked because current quotes are unavailable.");
        }
    }

    private record ChartWindow(String interval, int days, String labelFormat) { }

    public record StockDetails(
            String symbol,
            String name,
            String exchange,
            java.math.BigDecimal price,
            java.math.BigDecimal dayChange,
            java.math.BigDecimal dayChangePercentage,
            String direction,
            java.math.BigDecimal openPrice,
            java.math.BigDecimal prevClose,
            java.math.BigDecimal dayHigh,
            java.math.BigDecimal dayLow,
            java.math.BigDecimal yearHigh52,
            java.math.BigDecimal yearLow52,
            Long volume,
            java.math.BigDecimal upperCircuit,
            java.math.BigDecimal lowerCircuit,
            String marketCap,
            String peRatio,
            String beta,
            String sector
    ) { }

    public record ChartPoint(
            String time,
            java.math.BigDecimal price,
            java.math.BigDecimal open,
            java.math.BigDecimal high,
            java.math.BigDecimal low,
            java.math.BigDecimal close,
            long volume
    ) { }
}
