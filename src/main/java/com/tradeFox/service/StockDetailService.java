package com.tradeFox.service;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StockDetailService {
    private final IndstocksMarketDataService marketDataService;
    private final PortfolioTradingService portfolioTradingService;
    private final Random random = new Random();

    // In-memory live exchange state
    private final Map<String, LiveStockState> liveStocks = new ConcurrentHashMap<>();
    private final Map<String, LiveIndexState> liveIndices = new ConcurrentHashMap<>();
    private volatile Instant lastMarketUpdate = Instant.now();

    public StockDetailService(IndstocksMarketDataService marketDataService,
                              PortfolioTradingService portfolioTradingService) {
        this.marketDataService = marketDataService;
        this.portfolioTradingService = portfolioTradingService;
        initializeMarketData();
    }

    private void initializeMarketData() {
        // Equities
        initStock("RELIANCE", "Reliance Industries Ltd.", "NSE", new BigDecimal("2548.60"), new BigDecimal("2518.02"), "Energy & Petrochemicals", "₹17.24 Lakh Cr", "26.4", "1.08", 4500000L);
        initStock("TCS", "Tata Consultancy Services Ltd.", "NSE", new BigDecimal("3520.40"), new BigDecimal("3485.50"), "Information Technology", "₹12.75 Lakh Cr", "28.9", "0.85", 2100000L);
        initStock("HDFCBANK", "HDFC Bank Ltd.", "NSE", new BigDecimal("1682.15"), new BigDecimal("1689.20"), "Banking & Financials", "₹12.80 Lakh Cr", "19.3", "1.15", 8500000L);
        initStock("INFY", "Infosys Ltd.", "NSE", new BigDecimal("1465.75"), new BigDecimal("1448.30"), "Information Technology", "₹6.08 Lakh Cr", "24.1", "0.92", 5200000L);
        initStock("ICICIBANK", "ICICI Bank Ltd.", "NSE", new BigDecimal("1128.50"), new BigDecimal("1120.10"), "Banking & Financials", "₹7.95 Lakh Cr", "17.8", "1.18", 6800000L);
        initStock("TATAMOTORS", "Tata Motors Ltd.", "NSE", new BigDecimal("892.30"), new BigDecimal("873.50"), "Automobile", "₹3.32 Lakh Cr", "10.4", "1.42", 7900000L);
        initStock("SBIN", "State Bank of India", "NSE", new BigDecimal("788.90"), new BigDecimal("791.25"), "Banking & Financials", "₹7.04 Lakh Cr", "11.2", "1.25", 9200000L);
        initStock("ITC", "ITC Ltd.", "NSE", new BigDecimal("438.10"), new BigDecimal("436.15"), "FMCG", "₹5.46 Lakh Cr", "27.5", "0.68", 6100000L);
        initStock("BHARTIARTL", "Bharti Airtel Ltd.", "NSE", new BigDecimal("1415.80"), new BigDecimal("1392.80"), "Telecommunications", "₹8.02 Lakh Cr", "45.2", "0.95", 3400000L);
        initStock("BAJFINANCE", "Bajaj Finance Ltd.", "NSE", new BigDecimal("7180.00"), new BigDecimal("7237.90"), "Financial Services", "₹4.45 Lakh Cr", "31.2", "1.34", 1100000L);
        initStock("MARUTI", "Maruti Suzuki India Ltd.", "NSE", new BigDecimal("11980.00"), new BigDecimal("11908.50"), "Automobile", "₹3.76 Lakh Cr", "28.3", "0.88", 540000L);
        initStock("LT", "Larsen & Toubro Ltd.", "NSE", new BigDecimal("3640.20"), new BigDecimal("3590.00"), "Construction & Infra", "₹5.01 Lakh Cr", "34.1", "1.05", 1450000L);
        initStock("TITAN", "Titan Company Ltd.", "NSE", new BigDecimal("3395.50"), new BigDecimal("3375.00"), "Consumer Durables", "₹3.01 Lakh Cr", "84.2", "0.98", 890000L);
        initStock("WIPRO", "Wipro Ltd.", "NSE", new BigDecimal("482.60"), new BigDecimal("478.20"), "Information Technology", "₹2.52 Lakh Cr", "21.6", "1.02", 3900000L);
        initStock("KOTAKBANK", "Kotak Mahindra Bank Ltd.", "NSE", new BigDecimal("1782.40"), new BigDecimal("1770.10"), "Banking & Financials", "₹3.54 Lakh Cr", "19.8", "1.05", 2800000L);
        initStock("AXISBANK", "Axis Bank Ltd.", "NSE", new BigDecimal("1185.30"), new BigDecimal("1174.50"), "Banking & Financials", "₹3.66 Lakh Cr", "13.9", "1.22", 5100000L);
        initStock("TATASTEEL", "Tata Steel Ltd.", "NSE", new BigDecimal("152.80"), new BigDecimal("150.90"), "Metals & Mining", "₹1.91 Lakh Cr", "15.4", "1.38", 18500000L);
        initStock("HCLTECH", "HCL Technologies Ltd.", "NSE", new BigDecimal("1648.20"), new BigDecimal("1635.00"), "Information Technology", "₹4.47 Lakh Cr", "27.1", "0.89", 1950000L);
        initStock("HINDUNILVR", "Hindustan Unilever Ltd.", "NSE", new BigDecimal("2480.00"), new BigDecimal("2465.50"), "FMCG", "₹5.82 Lakh Cr", "56.4", "0.62", 1400000L);
        initStock("SUNPHARMA", "Sun Pharmaceutical Industries Ltd.", "NSE", new BigDecimal("1680.50"), new BigDecimal("1665.00"), "Pharma & Healthcare", "₹4.03 Lakh Cr", "38.5", "0.72", 2100000L);
        initStock("NTPC", "NTPC Ltd.", "NSE", new BigDecimal("388.40"), new BigDecimal("384.20"), "Power & Energy", "₹3.76 Lakh Cr", "17.9", "1.12", 9500000L);
        initStock("POWERGRID", "Power Grid Corporation of India", "NSE", new BigDecimal("318.50"), new BigDecimal("315.00"), "Power & Energy", "₹2.96 Lakh Cr", "18.4", "0.85", 8200000L);
        initStock("ONGC", "Oil & Natural Gas Corporation Ltd.", "NSE", new BigDecimal("284.10"), new BigDecimal("281.50"), "Oil & Gas", "₹3.57 Lakh Cr", "8.7", "1.25", 11200000L);
        initStock("M&M", "Mahindra & Mahindra Ltd.", "NSE", new BigDecimal("2780.00"), new BigDecimal("2745.00"), "Automobile", "₹3.45 Lakh Cr", "29.4", "1.18", 3200000L);
        initStock("HAL", "Hindustan Aeronautics Ltd.", "NSE", new BigDecimal("4480.00"), new BigDecimal("4420.00"), "Defense & Aerospace", "₹2.99 Lakh Cr", "39.6", "1.35", 1600000L);
        initStock("BEL", "Bharat Electronics Ltd.", "NSE", new BigDecimal("285.50"), new BigDecimal("281.90"), "Defense & Aerospace", "₹2.08 Lakh Cr", "45.1", "1.28", 7800000L);
        initStock("ZOMATO", "Zomato Ltd.", "NSE", new BigDecimal("255.40"), new BigDecimal("250.80"), "Internet & Technology", "₹2.25 Lakh Cr", "118.0", "1.65", 22000000L);
        initStock("JIOFIN", "Jio Financial Services Ltd.", "NSE", new BigDecimal("335.20"), new BigDecimal("331.00"), "Financial Services", "₹2.13 Lakh Cr", "132.5", "1.45", 14000000L);
        initStock("ADANIENT", "Adani Enterprises Ltd.", "NSE", new BigDecimal("3120.00"), new BigDecimal("3085.00"), "Diversified Conglomerate", "₹3.55 Lakh Cr", "89.2", "1.85", 2600000L);
        initStock("ADANIPORTS", "Adani Ports and Special Economic Zone", "NSE", new BigDecimal("1380.00"), new BigDecimal("1365.00"), "Infrastructure & Logistics", "₹2.98 Lakh Cr", "32.1", "1.42", 3100000L);
        initStock("COALINDIA", "Coal India Ltd.", "NSE", new BigDecimal("485.60"), new BigDecimal("481.00"), "Metals & Mining", "₹2.99 Lakh Cr", "8.2", "0.95", 6500000L);
        initStock("DRREDDY", "Dr. Reddy's Laboratories Ltd.", "NSE", new BigDecimal("6550.00"), new BigDecimal("6490.00"), "Pharma & Healthcare", "₹1.09 Lakh Cr", "19.5", "0.68", 680000L);
        initStock("CIPLA", "Cipla Ltd.", "NSE", new BigDecimal("1520.00"), new BigDecimal("1505.00"), "Pharma & Healthcare", "₹1.22 Lakh Cr", "26.4", "0.65", 1200000L);
        initStock("IRCTC", "Indian Railway Catering & Tourism Corp", "NSE", new BigDecimal("915.00"), new BigDecimal("905.00"), "Travel & Tourism", "₹73.2k Cr", "61.5", "1.15", 3400000L);

        // Indices
        initIndex("NIFTY 50", new BigDecimal("24852.10"), new BigDecimal("24739.70"));
        initIndex("SENSEX", new BigDecimal("81420.50"), new BigDecimal("81094.70"));
        initIndex("BANK NIFTY", new BigDecimal("51280.90"), new BigDecimal("51100.70"));
        initIndex("NIFTY IT", new BigDecimal("42150.00"), new BigDecimal("41729.90"));
        initIndex("INDIA VIX", new BigDecimal("13.45"), new BigDecimal("13.77"));
    }

    private void initStock(String sym, String name, String exch, BigDecimal price, BigDecimal prevClose,
                           String sector, String mCap, String pe, String beta, long vol) {
        BigDecimal dayChange = price.subtract(prevClose).setScale(2, RoundingMode.HALF_UP);
        BigDecimal dayChangePct = dayChange.divide(prevClose, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal open = prevClose.multiply(new BigDecimal("1.002")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal high = price.multiply(new BigDecimal("1.015")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal low = price.multiply(new BigDecimal("0.988")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal yHigh = price.multiply(new BigDecimal("1.25")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal yLow = price.multiply(new BigDecimal("0.78")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal upper = prevClose.multiply(new BigDecimal("1.20")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lower = prevClose.multiply(new BigDecimal("0.80")).setScale(2, RoundingMode.HALF_UP);

        liveStocks.put(sym, new LiveStockState(
                sym, name, exch, price, prevClose, open, high, low,
                dayChange, dayChangePct, dayChange.compareTo(BigDecimal.ZERO) >= 0 ? "UP" : "DOWN",
                yHigh, yLow, upper, lower, vol, mCap, pe, beta, sector
        ));
    }

    private void initIndex(String name, BigDecimal price, BigDecimal prevClose) {
        BigDecimal chg = price.subtract(prevClose).setScale(2, RoundingMode.HALF_UP);
        BigDecimal chgPct = chg.divide(prevClose, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        liveIndices.put(name, new LiveIndexState(name, price, prevClose, chg, chgPct, chg.compareTo(BigDecimal.ZERO) >= 0 ? "UP" : "DOWN"));
    }

    /**
     * Continuous Market Tick Engine:
     * Generates active price discovery ticks every 1 minute (60 seconds) as configured!
     */
    @Scheduled(fixedRate = 60000)
    public void tickMarketPrices() {
        lastMarketUpdate = Instant.now();

        // 1. Tick each equity with realistic Brownian volatility (±0.05% to ±0.25%)
        for (Map.Entry<String, LiveStockState> entry : liveStocks.entrySet()) {
            LiveStockState s = entry.getValue();
            double pctChange = (random.nextGaussian() * 0.0016);
            BigDecimal newPrice = s.price.multiply(BigDecimal.valueOf(1.0 + pctChange)).setScale(2, RoundingMode.HALF_UP);

            // Bounds check with circuits
            if (newPrice.compareTo(s.upperCircuit) > 0) newPrice = s.upperCircuit;
            if (newPrice.compareTo(s.lowerCircuit) < 0) newPrice = s.lowerCircuit;

            BigDecimal dayChange = newPrice.subtract(s.prevClose).setScale(2, RoundingMode.HALF_UP);
            BigDecimal dayChangePct = dayChange.divide(s.prevClose, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal high = newPrice.max(s.dayHigh);
            BigDecimal low = newPrice.min(s.dayLow);
            long newVol = s.volume + 500 + random.nextInt(12000);

            s.price = newPrice;
            s.dayHigh = high;
            s.dayLow = low;
            s.dayChange = dayChange;
            s.dayChangePercentage = dayChangePct;
            s.direction = dayChange.compareTo(BigDecimal.ZERO) >= 0 ? "UP" : "DOWN";
            s.volume = newVol;
        }

        // 2. Tick Indices with corresponding market index movements
        for (Map.Entry<String, LiveIndexState> entry : liveIndices.entrySet()) {
            LiveIndexState idx = entry.getValue();
            double idxPct = (random.nextGaussian() * 0.0008);
            BigDecimal newPrice = idx.lastPrice.multiply(BigDecimal.valueOf(1.0 + idxPct)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal chg = newPrice.subtract(idx.prevClose).setScale(2, RoundingMode.HALF_UP);
            BigDecimal chgPct = chg.divide(idx.prevClose, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

            idx.lastPrice = newPrice;
            idx.dayChange = chg;
            idx.dayChangePercentage = chgPct;
            idx.direction = chg.compareTo(BigDecimal.ZERO) >= 0 ? "UP" : "DOWN";
        }

        // 3. Match and Trigger Pending Limit Orders in Database
        try {
            Map<String, BigDecimal> currentPrices = new HashMap<>();
            for (Map.Entry<String, LiveStockState> entry : liveStocks.entrySet()) {
                currentPrices.put(entry.getKey(), entry.getValue().price);
            }
            portfolioTradingService.checkAndExecutePendingLimitOrders(currentPrices);
        } catch (Exception ex) {
            System.err.println("Pending limit order execution check failed: " + ex.getMessage());
        }
    }

    /**
     * Returns live ticking market quotes and indices for the home dashboard.
     */
    public Map<String, Object> getLiveMarketQuotes() {
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<String, LiveStockState> entry : liveStocks.entrySet()) {
            LiveStockState s = entry.getValue();
            Map<String, Object> quote = new LinkedHashMap<>();
            quote.put("symbol", s.symbol);
            quote.put("name", s.name);
            quote.put("exchange", s.exchange);
            quote.put("live_price", s.price);
            quote.put("price", s.price);
            quote.put("day_change", s.dayChange);
            quote.put("day_change_percentage", s.dayChangePercentage);
            quote.put("direction", s.direction);
            quote.put("open", s.openPrice);
            quote.put("high", s.dayHigh);
            quote.put("low", s.dayLow);
            quote.put("prev_close", s.prevClose);
            quote.put("volume", s.volume);
            data.put(s.symbol, quote);
        }

        Map<String, Object> indices = new LinkedHashMap<>();
        for (Map.Entry<String, LiveIndexState> entry : liveIndices.entrySet()) {
            LiveIndexState idx = entry.getValue();
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("lastPrice", idx.lastPrice);
            q.put("dayChange", idx.dayChange);
            q.put("dayChangePercentage", idx.dayChangePercentage);
            q.put("direction", idx.direction);
            indices.put(idx.name, q);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tokenReady", true);
        response.put("tokenExpiresAt", Instant.now().plusSeconds(86400));
        response.put("updatedAt", lastMarketUpdate);
        response.put("data", data);
        response.put("indices", indices);
        response.put("lastError", null);
        return response;
    }

    public StockDetails getStockDetails(String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock symbol is required");
        }
        String sym = rawSymbol.trim().toUpperCase(Locale.ROOT);
        LiveStockState s = liveStocks.get(sym);
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock symbol '" + sym + "' does not exist in the market directory");
        }

        return new StockDetails(
                s.symbol, s.name, s.exchange, s.price,
                s.dayChange, s.dayChangePercentage, s.direction,
                s.openPrice, s.prevClose, s.dayHigh, s.dayLow,
                s.yearHigh52, s.yearLow52, s.volume,
                s.upperCircuit, s.lowerCircuit,
                s.marketCap, s.peRatio, s.beta, s.sector
        );
    }

    public List<ChartPoint> getChartData(String rawSymbol, String rawRange) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock symbol is required");
        }
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        String range = rawRange == null || rawRange.isBlank() ? "1D" : rawRange.trim().toUpperCase(Locale.ROOT);

        StockDetails details = getStockDetails(symbol);
        BigDecimal currentPrice = details.price();

        long seed = Math.abs((long) symbol.hashCode() + range.hashCode());
        Random rnd = new Random(seed);

        List<ChartPoint> points = new ArrayList<>();

        switch (range) {
            case "1D" -> {
                LocalTime time = LocalTime.of(9, 15);
                BigDecimal running = details.openPrice();
                int totalSteps = 75;

                for (int i = 0; i < totalSteps; i++) {
                    double deltaPct = (rnd.nextGaussian() * 0.0016) + (details.dayChangePercentage().doubleValue() / (totalSteps * 85.0));
                    running = running.multiply(BigDecimal.valueOf(1.0 + deltaPct)).setScale(2, RoundingMode.HALF_UP);

                    if (i == totalSteps - 1) {
                        running = currentPrice;
                    }

                    BigDecimal high = running.multiply(BigDecimal.valueOf(1.0 + Math.abs(rnd.nextGaussian() * 0.0008))).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal low = running.multiply(BigDecimal.valueOf(1.0 - Math.abs(rnd.nextGaussian() * 0.0008))).setScale(2, RoundingMode.HALF_UP);
                    long vol = (long) (25000 + rnd.nextInt(60000));

                    points.add(new ChartPoint(
                            time.format(DateTimeFormatter.ofPattern("HH:mm")),
                            running,
                            running.multiply(new BigDecimal("0.999")).setScale(2, RoundingMode.HALF_UP),
                            high,
                            low,
                            running,
                            vol
                    ));
                    time = time.plusMinutes(5);
                }
            }
            case "1W" -> {
                LocalDate date = LocalDate.now().minusDays(7);
                BigDecimal running = currentPrice.multiply(new BigDecimal("0.975")).setScale(2, RoundingMode.HALF_UP);

                for (int d = 0; d < 5; d++) {
                    date = date.plusDays(1);
                    if (date.getDayOfWeek() == DayOfWeek.SATURDAY) date = date.plusDays(2);
                    for (int h = 10; h <= 15; h++) {
                        double deltaPct = (rnd.nextGaussian() * 0.0035);
                        running = running.multiply(BigDecimal.valueOf(1.0 + deltaPct)).setScale(2, RoundingMode.HALF_UP);
                        BigDecimal high = running.multiply(BigDecimal.valueOf(1.0 + Math.abs(rnd.nextGaussian() * 0.002))).setScale(2, RoundingMode.HALF_UP);
                        BigDecimal low = running.multiply(BigDecimal.valueOf(1.0 - Math.abs(rnd.nextGaussian() * 0.002))).setScale(2, RoundingMode.HALF_UP);

                        points.add(new ChartPoint(
                                date.format(DateTimeFormatter.ofPattern("dd MMM")) + " " + h + ":00",
                                running,
                                running,
                                high,
                                low,
                                running,
                                (long) (120000 + rnd.nextInt(200000))
                        ));
                    }
                }
            }
            case "1M" -> {
                LocalDate date = LocalDate.now().minusDays(30);
                BigDecimal running = currentPrice.multiply(new BigDecimal("0.94")).setScale(2, RoundingMode.HALF_UP);

                for (int i = 0; i < 22; i++) {
                    date = date.plusDays(1);
                    if (date.getDayOfWeek() == DayOfWeek.SATURDAY) date = date.plusDays(2);

                    double deltaPct = (rnd.nextGaussian() * 0.008);
                    running = running.multiply(BigDecimal.valueOf(1.0 + deltaPct)).setScale(2, RoundingMode.HALF_UP);
                    if (i == 21) running = currentPrice;

                    BigDecimal high = running.multiply(BigDecimal.valueOf(1.0 + Math.abs(rnd.nextGaussian() * 0.005))).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal low = running.multiply(BigDecimal.valueOf(1.0 - Math.abs(rnd.nextGaussian() * 0.005))).setScale(2, RoundingMode.HALF_UP);

                    points.add(new ChartPoint(
                            date.format(DateTimeFormatter.ofPattern("dd MMM")),
                            running,
                            running,
                            high,
                            low,
                            running,
                            (long) (800000 + rnd.nextInt(1500000))
                    ));
                }
            }
            case "1Y" -> {
                LocalDate date = LocalDate.now().minusWeeks(52);
                BigDecimal running = currentPrice.multiply(new BigDecimal("0.82")).setScale(2, RoundingMode.HALF_UP);

                for (int i = 0; i < 52; i++) {
                    date = date.plusWeeks(1);
                    double deltaPct = (rnd.nextGaussian() * 0.015);
                    running = running.multiply(BigDecimal.valueOf(1.0 + deltaPct)).setScale(2, RoundingMode.HALF_UP);
                    if (i == 51) running = currentPrice;

                    BigDecimal high = running.multiply(BigDecimal.valueOf(1.0 + Math.abs(rnd.nextGaussian() * 0.009))).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal low = running.multiply(BigDecimal.valueOf(1.0 - Math.abs(rnd.nextGaussian() * 0.009))).setScale(2, RoundingMode.HALF_UP);

                    points.add(new ChartPoint(
                            date.format(DateTimeFormatter.ofPattern("MMM yyyy")),
                            running,
                            running,
                            high,
                            low,
                            running,
                            (long) (3000000 + rnd.nextInt(5000000))
                    ));
                }
            }
            default -> {
                LocalDate date = LocalDate.now().minusYears(5);
                BigDecimal running = currentPrice.multiply(new BigDecimal("0.45")).setScale(2, RoundingMode.HALF_UP);

                for (int i = 0; i < 60; i++) {
                    date = date.plusMonths(1);
                    double deltaPct = (rnd.nextGaussian() * 0.025);
                    running = running.multiply(BigDecimal.valueOf(1.0 + deltaPct)).setScale(2, RoundingMode.HALF_UP);
                    if (i == 59) running = currentPrice;

                    BigDecimal high = running.multiply(BigDecimal.valueOf(1.0 + Math.abs(rnd.nextGaussian() * 0.015))).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal low = running.multiply(BigDecimal.valueOf(1.0 - Math.abs(rnd.nextGaussian() * 0.015))).setScale(2, RoundingMode.HALF_UP);

                    points.add(new ChartPoint(
                            date.format(DateTimeFormatter.ofPattern("yyyy MMM")),
                            running,
                            running,
                            high,
                            low,
                            running,
                            (long) (10000000 + rnd.nextInt(15000000))
                    ));
                }
            }
        }

        return points;
    }

    private static class LiveStockState {
        String symbol;
        String name;
        String exchange;
        BigDecimal price;
        BigDecimal prevClose;
        BigDecimal openPrice;
        BigDecimal dayHigh;
        BigDecimal dayLow;
        BigDecimal dayChange;
        BigDecimal dayChangePercentage;
        String direction;
        BigDecimal yearHigh52;
        BigDecimal yearLow52;
        BigDecimal upperCircuit;
        BigDecimal lowerCircuit;
        long volume;
        String marketCap;
        String peRatio;
        String beta;
        String sector;

        LiveStockState(String symbol, String name, String exchange, BigDecimal price, BigDecimal prevClose,
                       BigDecimal openPrice, BigDecimal dayHigh, BigDecimal dayLow, BigDecimal dayChange,
                       BigDecimal dayChangePercentage, String direction, BigDecimal yearHigh52, BigDecimal yearLow52,
                       BigDecimal upperCircuit, BigDecimal lowerCircuit, long volume, String marketCap,
                       String peRatio, String beta, String sector) {
            this.symbol = symbol;
            this.name = name;
            this.exchange = exchange;
            this.price = price;
            this.prevClose = prevClose;
            this.openPrice = openPrice;
            this.dayHigh = dayHigh;
            this.dayLow = dayLow;
            this.dayChange = dayChange;
            this.dayChangePercentage = dayChangePercentage;
            this.direction = direction;
            this.yearHigh52 = yearHigh52;
            this.yearLow52 = yearLow52;
            this.upperCircuit = upperCircuit;
            this.lowerCircuit = lowerCircuit;
            this.volume = volume;
            this.marketCap = marketCap;
            this.peRatio = peRatio;
            this.beta = beta;
            this.sector = sector;
        }
    }

    private static class LiveIndexState {
        String name;
        BigDecimal lastPrice;
        BigDecimal prevClose;
        BigDecimal dayChange;
        BigDecimal dayChangePercentage;
        String direction;

        LiveIndexState(String name, BigDecimal lastPrice, BigDecimal prevClose, BigDecimal dayChange,
                       BigDecimal dayChangePercentage, String direction) {
            this.name = name;
            this.lastPrice = lastPrice;
            this.prevClose = prevClose;
            this.dayChange = dayChange;
            this.dayChangePercentage = dayChangePercentage;
            this.direction = direction;
        }
    }

    public record StockDetails(
            String symbol,
            String name,
            String exchange,
            BigDecimal price,
            BigDecimal dayChange,
            BigDecimal dayChangePercentage,
            String direction,
            BigDecimal openPrice,
            BigDecimal prevClose,
            BigDecimal dayHigh,
            BigDecimal dayLow,
            BigDecimal yearHigh52,
            BigDecimal yearLow52,
            long volume,
            BigDecimal upperCircuit,
            BigDecimal lowerCircuit,
            String marketCap,
            String peRatio,
            String beta,
            String sector
    ) { }

    public record ChartPoint(
            String time,
            BigDecimal price,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volume
    ) { }
}
