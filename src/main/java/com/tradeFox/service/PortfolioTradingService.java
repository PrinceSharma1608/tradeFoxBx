package com.tradeFox.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PortfolioTradingService {
    private static final BigDecimal MAX_WALLET_BALANCE = new BigDecimal("9999999999999.99");
    private static final BigDecimal MAX_ORDER_AMOUNT = new BigDecimal("9999999999.99");

    private final JdbcTemplate db;
    private final IndstocksMarketDataService marketDataService;

    public PortfolioTradingService(JdbcTemplate db, IndstocksMarketDataService marketDataService) {
        this.db = db;
        this.marketDataService = marketDataService;
    }

    private static final Map<String, String> CANONICAL_STOCK_NAMES = Map.ofEntries(
            // Nifty 50 & Large Caps
            Map.entry("RELIANCE", "Reliance Industries Ltd."),
            Map.entry("TCS", "Tata Consultancy Services Ltd."),
            Map.entry("HDFCBANK", "HDFC Bank Ltd."),
            Map.entry("INFY", "Infosys Ltd."),
            Map.entry("ICICIBANK", "ICICI Bank Ltd."),
            Map.entry("TATAMOTORS", "Tata Motors Ltd."),
            Map.entry("SBIN", "State Bank of India"),
            Map.entry("ITC", "ITC Ltd."),
            Map.entry("BHARTIARTL", "Bharti Airtel Ltd."),
            Map.entry("BAJFINANCE", "Bajaj Finance Ltd."),
            Map.entry("MARUTI", "Maruti Suzuki India Ltd."),
            Map.entry("LT", "Larsen & Toubro Ltd."),
            Map.entry("TITAN", "Titan Company Ltd."),
            Map.entry("WIPRO", "Wipro Ltd."),
            Map.entry("KOTAKBANK", "Kotak Mahindra Bank Ltd."),
            Map.entry("AXISBANK", "Axis Bank Ltd."),
            Map.entry("TATASTEEL", "Tata Steel Ltd."),
            Map.entry("HCLTECH", "HCL Technologies Ltd."),
            Map.entry("HINDUNILVR", "Hindustan Unilever Ltd."),
            Map.entry("SUNPHARMA", "Sun Pharmaceutical Industries Ltd."),
            Map.entry("NTPC", "NTPC Ltd."),
            Map.entry("POWERGRID", "Power Grid Corporation of India"),
            Map.entry("ONGC", "Oil & Natural Gas Corporation Ltd."),
            Map.entry("M&M", "Mahindra & Mahindra Ltd."),
            Map.entry("HAL", "Hindustan Aeronautics Ltd."),
            Map.entry("BEL", "Bharat Electronics Ltd."),
            Map.entry("ZOMATO", "Zomato Ltd."),
            Map.entry("JIOFIN", "Jio Financial Services Ltd."),
            Map.entry("ADANIENT", "Adani Enterprises Ltd."),
            Map.entry("ADANIPORTS", "Adani Ports and SEZ Ltd."),
            Map.entry("COALINDIA", "Coal India Ltd."),
            Map.entry("DRREDDY", "Dr. Reddy's Laboratories Ltd."),
            Map.entry("CIPLA", "Cipla Ltd."),
            Map.entry("IRCTC", "Indian Railway Catering & Tourism Corp"),
            // High-Demand Power, Railways & PSU Giants
            Map.entry("TATAPOWER", "Tata Power Company Ltd."),
            Map.entry("SUZLON", "Suzlon Energy Ltd."),
            Map.entry("IREDA", "Indian Renewable Energy Dev Agency"),
            Map.entry("RVNL", "Rail Vikas Nigam Ltd."),
            Map.entry("IRFC", "Indian Railway Finance Corporation"),
            Map.entry("BHEL", "Bharat Heavy Electricals Ltd."),
            Map.entry("NHPC", "NHPC Ltd."),
            Map.entry("SJVN", "SJVN Ltd."),
            Map.entry("ADANIGREEN", "Adani Green Energy Ltd."),
            Map.entry("ADANIPOWER", "Adani Power Ltd."),
            // Retail, FMCG & Aviation
            Map.entry("TRENT", "Trent Ltd."),
            Map.entry("DMART", "Avenue Supermarts Ltd."),
            Map.entry("ASIANPAINT", "Asian Paints Ltd."),
            Map.entry("NESTLEIND", "Nestle India Ltd."),
            Map.entry("BRITANNIA", "Britannia Industries Ltd."),
            Map.entry("PIDILITIND", "Pidilite Industries Ltd."),
            Map.entry("BERGEPAINT", "Berger Paints India Ltd."),
            Map.entry("INDIGO", "InterGlobe Aviation Ltd."),
            Map.entry("DLF", "DLF Ltd."),
            // Defense & Shipbuilders
            Map.entry("MAZDOCK", "Mazagon Dock Shipbuilders Ltd."),
            Map.entry("COCHINSHIP", "Cochin Shipyard Ltd."),
            Map.entry("BDL", "Bharat Dynamics Ltd."),
            // Banking, NBFC & Insurance
            Map.entry("PNB", "Punjab National Bank"),
            Map.entry("BANKBARODA", "Bank of Baroda"),
            Map.entry("CANBK", "Canara Bank"),
            Map.entry("IDFCFIRSTB", "IDFC First Bank Ltd."),
            Map.entry("FEDERALBNK", "The Federal Bank Ltd."),
            Map.entry("MUTHOOTFIN", "Muthoot Finance Ltd."),
            Map.entry("CHOLAFIN", "Cholamandalam Investment & Finance"),
            Map.entry("SHRIRAMFIN", "Shriram Finance Ltd."),
            Map.entry("LICI", "Life Insurance Corporation of India"),
            Map.entry("SBILIFE", "SBI Life Insurance Company Ltd."),
            Map.entry("HDFCLIFE", "HDFC Life Insurance Company Ltd."),
            // Fintech & Depository
            Map.entry("PAYTM", "One97 Communications Ltd. (Paytm)"),
            Map.entry("CDSL", "Central Depository Services Ltd."),
            Map.entry("ANGELONE", "Angel One Ltd."),
            // Auto & Mobility
            Map.entry("EICHERMOT", "Eicher Motors Ltd."),
            Map.entry("HEROMOTOCO", "Hero MotoCorp Ltd."),
            Map.entry("BAJAJ-AUTO", "Bajaj Auto Ltd."),
            Map.entry("TVSMOTOR", "TVS Motor Company Ltd."),
            Map.entry("MOTHERSON", "Samvardhana Motherson International"),
            // IT Services
            Map.entry("TECHM", "Tech Mahindra Ltd."),
            Map.entry("LTIM", "LTIMindtree Ltd."),
            Map.entry("PERSISTENT", "Persistent Systems Ltd."),
            Map.entry("COFORGE", "Coforge Ltd."),
            // Metals, Mining, Oil & Gas
            Map.entry("VEDL", "Vedanta Ltd."),
            Map.entry("JSWSTEEL", "JSW Steel Ltd."),
            Map.entry("HINDALCO", "Hindalco Industries Ltd."),
            Map.entry("JINDALSTEL", "Jindal Steel & Power Ltd."),
            Map.entry("NMDC", "NMDC Ltd."),
            Map.entry("BPCL", "Bharat Petroleum Corporation Ltd."),
            Map.entry("IOC", "Indian Oil Corporation Ltd."),
            Map.entry("GAIL", "GAIL (India) Ltd."),
            // Cement & Telecom
            Map.entry("ULTRACEMCO", "UltraTech Cement Ltd."),
            Map.entry("AMBUJACEM", "Ambuja Cements Ltd."),
            Map.entry("GRASIM", "Grasim Industries Ltd."),
            Map.entry("IDEA", "Vodafone Idea Ltd."),
            // Healthcare & Pharma
            Map.entry("APOLLOHOSP", "Apollo Hospitals Enterprise Ltd."),
            Map.entry("MAXHEALTH", "Max Healthcare Institute Ltd."),
            Map.entry("MANKIND", "Mankind Pharma Ltd."),
            Map.entry("LUPIN", "Lupin Ltd."),
            Map.entry("AUROPHARMA", "Aurobindo Pharma Ltd.")
    );

    public static String resolveStockName(String symbol, String providedName) {
        if (symbol != null) {
            String canonical = CANONICAL_STOCK_NAMES.get(symbol.toUpperCase().trim());
            if (canonical != null) return canonical;
        }
        return (providedName != null && !providedName.isBlank()) ? providedName.trim() : (symbol != null ? symbol : "");
    }

    /**
     * Executes a BUY or SELL paper order and synchronizes users, orders, holdings, and transactions tables.
     */
    @Transactional
    public OrderResult executeOrder(long userId, String rawSymbol, String rawName,
                                    String rawOrderType, BigDecimal price, int quantity) {
        return executeOrder(userId, rawSymbol, rawName, rawOrderType, price, quantity, "MARKET", price);
    }

    @Transactional
    public OrderResult executeOrder(long userId, String rawSymbol, String rawName,
                                    String rawOrderType, BigDecimal price, int quantity,
                                    String rawExecutionType, BigDecimal currentMarketPrice) {
        return executeOrder(userId, rawSymbol, rawName, rawOrderType, price, quantity,
                rawExecutionType, currentMarketPrice, null);
    }

    @Transactional
    public OrderResult executeOrder(long userId, String rawSymbol, String rawName,
                                    String rawOrderType, BigDecimal requestedPrice, int quantity,
                                    String rawExecutionType, BigDecimal ignoredClientMarketPrice,
                                    String rawIdempotencyKey) {
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid user ID is required");
        }
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        String orderType = rawOrderType == null ? "" : rawOrderType.trim().toUpperCase(Locale.ROOT);
        String executionType = (rawExecutionType == null || rawExecutionType.isBlank())
                ? "MARKET" : rawExecutionType.trim().toUpperCase(Locale.ROOT);

        if (symbol.isBlank() || symbol.length() > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock symbol must be between 1 and 30 characters");
        }
        if (!"BUY".equals(orderType) && !"SELL".equals(orderType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order type must be either BUY or SELL");
        }
        if (!"MARKET".equals(executionType) && !"LIMIT".equals(executionType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execution type must be MARKET or LIMIT");
        }
        if (quantity <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be greater than zero");
        }

        BigDecimal limitPrice = requestedPrice;
        if ("LIMIT".equals(executionType)
                && (limitPrice == null || limitPrice.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit price must be positive");
        }
        if (limitPrice != null && limitPrice.scale() > 2 && "LIMIT".equals(executionType)) {
            limitPrice = limitPrice.setScale(2, RoundingMode.HALF_UP);
        }

        String idempotencyKey = rawIdempotencyKey == null ? null : rawIdempotencyKey.trim();
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain between 1 and 100 characters");
        }
        String requestHash = hashOrderRequest(symbol, orderType, executionType,
                "LIMIT".equals(executionType) ? limitPrice : null, quantity);

        // A retry of a committed order can be replayed even when market data is temporarily unavailable.
        if (idempotencyKey != null) {
            List<StoredOrder> existing = findOrderByIdempotencyKey(userId, idempotencyKey);
            if (!existing.isEmpty()) {
                StoredOrder order = existing.getFirst();
                if (!requestHash.equals(order.requestHash())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Idempotency-Key was already used for a different order");
                }
                List<BigDecimal> balance = db.query(
                        "SELECT wallet_balance FROM users WHERE user_id = ? AND is_active = TRUE",
                        (rs, rowNum) -> rs.getBigDecimal("wallet_balance"), userId);
                if (balance.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active user not found");
                return replay(order, balance.getFirst());
            }
        }

        IndstocksMarketDataService.StockQuote quote;
        try {
            quote = marketDataService.getStockQuote(symbol);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported stock symbol");
            }
            throw exception;
        }
        if (quote.price() == null || quote.price().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Market provider returned an invalid stock price");
        }
        String name = quote.stockName();
        BigDecimal price = "LIMIT".equals(executionType)
                ? limitPrice : quote.price().setScale(2, RoundingMode.HALF_UP);
        BigDecimal effectiveMarketPrice = quote.price();
        BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        if (price.compareTo(MAX_ORDER_AMOUNT) > 0 || totalAmount.compareTo(MAX_ORDER_AMOUNT) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order amount exceeds the supported limit");
        }

        // This lock serializes wallet and holding changes for this user, including simultaneous orders.
        List<BigDecimal> balances = db.query("""
                        SELECT wallet_balance FROM users
                        WHERE user_id = ? AND is_active = TRUE
                        FOR UPDATE
                        """,
                (rs, rowNum) -> rs.getBigDecimal("wallet_balance"), userId);
        if (balances.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active user not found");
        BigDecimal currentBalance = balances.getFirst();

        if (idempotencyKey != null) {
            List<StoredOrder> existing = db.query("""
                            SELECT order_id, user_id, stock_symbol, stock_name, order_type,
                                   order_price, quantity, order_status, request_hash
                            FROM orders WHERE user_id = ? AND idempotency_key = ?
                            FOR UPDATE
                            """,
                    (rs, rowNum) -> new StoredOrder(rs.getLong("order_id"), rs.getLong("user_id"),
                            rs.getString("stock_symbol"), rs.getString("stock_name"), rs.getString("order_type"),
                            rs.getBigDecimal("order_price"), rs.getInt("quantity"), rs.getString("order_status"),
                            rs.getString("request_hash")), userId, idempotencyKey);
            if (!existing.isEmpty()) {
                StoredOrder order = existing.getFirst();
                if (!requestHash.equals(order.requestHash())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Idempotency-Key was already used for a different order");
                }
                return replay(order, currentBalance);
            }
        } else {
            // Also absorb an immediate retry from clients that have not added an idempotency header yet.
            List<StoredOrder> recent = db.query("""
                            SELECT order_id, user_id, stock_symbol, stock_name, order_type,
                                   order_price, quantity, order_status, request_hash
                            FROM orders
                            WHERE user_id = ? AND idempotency_key IS NULL
                              AND request_hash = ?
                              AND order_status IN ('EXECUTED', 'PENDING')
                              AND created_at >= CURRENT_TIMESTAMP - INTERVAL '5 seconds'
                            ORDER BY created_at DESC LIMIT 1
                            FOR UPDATE
                            """,
                    (rs, rowNum) -> new StoredOrder(rs.getLong("order_id"), rs.getLong("user_id"),
                            rs.getString("stock_symbol"), rs.getString("stock_name"), rs.getString("order_type"),
                            rs.getBigDecimal("order_price"), rs.getInt("quantity"), rs.getString("order_status"),
                            rs.getString("request_hash")), userId, requestHash);
            if (!recent.isEmpty()) return replay(recent.getFirst(), currentBalance);
        }

        // Check if LIMIT condition is satisfied immediately or should remain PENDING
        boolean isLimitOrder = "LIMIT".equals(executionType);
        boolean shouldRemainPending = false;

        if (isLimitOrder) {
            if ("BUY".equals(orderType)) {
                // BUY Limit triggers when market price drops to or below target limit price
                if (effectiveMarketPrice.compareTo(price) > 0) {
                    shouldRemainPending = true;
                }
            } else {
                // SELL Limit triggers when market price rises to or above target limit price
                if (effectiveMarketPrice.compareTo(price) < 0) {
                    shouldRemainPending = true;
                }
            }
        }

        BigDecimal newBalance = currentBalance;

        if ("BUY".equals(orderType)) {
            if (currentBalance.compareTo(totalAmount) < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        String.format("Insufficient wallet balance. Required: ₹%s, Available: ₹%s",
                                totalAmount.toPlainString(), currentBalance.toPlainString()));
            }

            // Reserve funds from wallet balance
            newBalance = currentBalance.subtract(totalAmount).setScale(2, RoundingMode.HALF_UP);
            db.update("UPDATE users SET wallet_balance = ? WHERE user_id = ?", newBalance, userId);

            if (!shouldRemainPending) {
                // Execute immediately into holdings
                updateHoldingOnBuy(userId, symbol, name, quantity, price, totalAmount);
            }
        } else {
            // SELL Order: Validate holding availability
            List<HoldingItem> existingHoldings = getHoldingItemsForStock(userId, symbol);
            if (existingHoldings.isEmpty() || existingHoldings.getFirst().quantity() < quantity) {
                int available = existingHoldings.isEmpty() ? 0 : existingHoldings.getFirst().quantity();
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        String.format("Insufficient shares to sell. Available: %d, Requested: %d", available, quantity));
            }

            if (!shouldRemainPending) {
                // Execute immediately: credit wallet balance & deduct holding
                newBalance = currentBalance.add(totalAmount).setScale(2, RoundingMode.HALF_UP);
                if (newBalance.compareTo(MAX_WALLET_BALANCE) > 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wallet balance exceeds the supported limit");
                }
                db.update("UPDATE users SET wallet_balance = ? WHERE user_id = ?", newBalance, userId);
                updateHoldingOnSell(userId, symbol, quantity, existingHoldings.getFirst());
            }
        }

        String finalStatus = shouldRemainPending ? "PENDING" : "EXECUTED";

        // Insert Order Record
        Long orderId = db.queryForObject("""
                INSERT INTO orders (user_id, stock_symbol, stock_name, order_type, order_price, quantity,
                                    order_status, idempotency_key, request_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                RETURNING order_id
                """,
                Long.class,
                userId, symbol, name, orderType, price.setScale(2, RoundingMode.HALF_UP), quantity,
                finalStatus, idempotencyKey, requestHash
        );

        if (!shouldRemainPending) {
            // Insert Transaction Record
            long stockId = Math.abs((long) symbol.hashCode());
            db.update("""
                    INSERT INTO transactions (user_id, stock_id, transaction_type, price, quantity, transaction_time)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    userId, stockId, orderType, price.setScale(2, RoundingMode.HALF_UP), quantity);
        }

        String message = shouldRemainPending
                ? String.format("Limit %s order placed for %d %s @ ₹%s (Trigger pending)", orderType, quantity, symbol, price.toPlainString())
                : String.format("%s order executed successfully for %d %s @ ₹%s", orderType, quantity, symbol, price.toPlainString());

        return new OrderResult(
                orderId != null ? orderId : 0L,
                userId,
                symbol,
                name,
                orderType,
                price.setScale(2, RoundingMode.HALF_UP),
                quantity,
                totalAmount,
                newBalance,
                finalStatus,
                message
        );
    }

    private OrderResult replay(StoredOrder order, BigDecimal currentBalance) {
        BigDecimal total = order.price().multiply(BigDecimal.valueOf(order.quantity()))
                .setScale(2, RoundingMode.HALF_UP);
        return new OrderResult(order.orderId(), order.userId(), order.symbol(), order.name(),
                order.type(), order.price(), order.quantity(), total, currentBalance,
                order.status(), "Duplicate request; returned the existing order.");
    }

    private List<StoredOrder> findOrderByIdempotencyKey(long userId, String idempotencyKey) {
        return db.query("""
                        SELECT order_id, user_id, stock_symbol, stock_name, order_type,
                               order_price, quantity, order_status, request_hash
                        FROM orders WHERE user_id = ? AND idempotency_key = ?
                        """,
                (rs, rowNum) -> new StoredOrder(rs.getLong("order_id"), rs.getLong("user_id"),
                        rs.getString("stock_symbol"), rs.getString("stock_name"), rs.getString("order_type"),
                        rs.getBigDecimal("order_price"), rs.getInt("quantity"), rs.getString("order_status"),
                        rs.getString("request_hash")), userId, idempotencyKey);
    }

    private String hashOrderRequest(String symbol, String type, String executionType,
                                    BigDecimal limitPrice, int quantity) {
        String value = symbol + "|" + type + "|" + executionType + "|"
                + (limitPrice == null ? "" : limitPrice.stripTrailingZeros().toPlainString()) + "|" + quantity;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not fingerprint the order request", exception);
        }
    }

    private record StoredOrder(long orderId, long userId, String symbol, String name,
                               String type, BigDecimal price, int quantity, String status,
                               String requestHash) { }

    private void updateHoldingOnBuy(long userId, String symbol, String name, int quantity, BigDecimal price, BigDecimal totalAmount) {
        List<HoldingItem> existingHoldings = getHoldingItemsForStock(userId, symbol);
        if (existingHoldings.isEmpty()) {
            db.update("""
                    INSERT INTO holdings (user_id, stock_symbol, stock_name, quantity, average_purchase_price, invested_amount, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    userId, symbol, name, quantity, price.setScale(2, RoundingMode.HALF_UP), totalAmount);
        } else {
            HoldingItem current = existingHoldings.getFirst();
            int updatedQty;
            try {
                updatedQty = Math.addExact(current.quantity(), quantity);
            } catch (ArithmeticException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Holding quantity exceeds the supported limit");
            }
            BigDecimal updatedInvested = current.investedAmount().add(totalAmount).setScale(2, RoundingMode.HALF_UP);
            if (updatedInvested.compareTo(MAX_ORDER_AMOUNT) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invested amount exceeds the supported limit");
            }
            BigDecimal updatedAvgPrice = updatedInvested.divide(BigDecimal.valueOf(updatedQty), 2, RoundingMode.HALF_UP);

            db.update("""
                    UPDATE holdings
                    SET quantity = ?, average_purchase_price = ?, invested_amount = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = ? AND stock_symbol = ?
                    """,
                    updatedQty, updatedAvgPrice, updatedInvested, userId, symbol);
        }
    }

    private void updateHoldingOnSell(long userId, String symbol, int quantity, HoldingItem current) {
        int remainingQty = current.quantity() - quantity;
        if (remainingQty <= 0) {
            db.update("DELETE FROM holdings WHERE user_id = ? AND stock_symbol = ?", userId, symbol);
        } else {
            BigDecimal remainingInvested = current.averagePurchasePrice()
                    .multiply(BigDecimal.valueOf(remainingQty))
                    .setScale(2, RoundingMode.HALF_UP);
            db.update("""
                    UPDATE holdings
                    SET quantity = ?, invested_amount = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = ? AND stock_symbol = ?
                    """,
                    remainingQty, remainingInvested, userId, symbol);
        }
    }

    private List<HoldingItem> getHoldingItemsForStock(long userId, String symbol) {
        return db.query("""
                SELECT holding_id, user_id, stock_symbol, stock_name, quantity, average_purchase_price, invested_amount, updated_at
                FROM holdings WHERE user_id = ? AND stock_symbol = ?
                """,
                (rs, rowNum) -> new HoldingItem(
                        rs.getLong("holding_id"),
                        rs.getLong("user_id"),
                        rs.getString("stock_symbol"),
                        rs.getString("stock_name"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("average_purchase_price"),
                        rs.getBigDecimal("invested_amount"),
                        rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : Instant.now()
                ),
                userId, symbol
        );
    }

    /**
     * Ticking Trigger Engine:
     * Scans and executes all PENDING Limit Orders when the market ticks past the order trigger price!
     */
    @Transactional
    public int checkAndExecutePendingLimitOrders(Map<String, BigDecimal> currentPrices) {
        if (currentPrices == null || currentPrices.isEmpty()) return 0;

        List<PendingOrder> pending = db.query("""
                SELECT order_id, user_id, stock_symbol, stock_name, order_type, order_price, quantity
                FROM orders
                WHERE order_status = 'PENDING'
                """,
                (rs, rowNum) -> new PendingOrder(
                        rs.getLong("order_id"),
                        rs.getLong("user_id"),
                        rs.getString("stock_symbol"),
                        rs.getString("stock_name"),
                        rs.getString("order_type"),
                        rs.getBigDecimal("order_price"),
                        rs.getInt("quantity")
                )
        );

        int executedCount = 0;
        for (PendingOrder po : pending) {
            BigDecimal ltp = currentPrices.get(po.stockSymbol());
            if (ltp == null || ltp.compareTo(BigDecimal.ZERO) <= 0) continue;

            boolean triggered = false;
            if ("BUY".equalsIgnoreCase(po.orderType()) && ltp.compareTo(po.orderPrice()) <= 0) {
                triggered = true;
            } else if ("SELL".equalsIgnoreCase(po.orderType()) && ltp.compareTo(po.orderPrice()) >= 0) {
                triggered = true;
            }

            if (triggered) {
                try {
                    executePendingOrder(po, ltp);
                    executedCount++;
                } catch (Exception ex) {
                    System.err.println("Could not trigger pending order #" + po.orderId() + ": " + ex.getMessage());
                }
            }
        }
        return executedCount;
    }

    private void executePendingOrder(PendingOrder po, BigDecimal executionPrice) {
        db.query("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("user_id"), po.userId());
        List<PendingOrder> stillPending = db.query("""
                        SELECT order_id, user_id, stock_symbol, stock_name, order_type, order_price, quantity
                        FROM orders WHERE order_id = ? AND order_status = 'PENDING' FOR UPDATE
                        """,
                (rs, rowNum) -> new PendingOrder(rs.getLong("order_id"), rs.getLong("user_id"),
                        rs.getString("stock_symbol"), rs.getString("stock_name"), rs.getString("order_type"),
                        rs.getBigDecimal("order_price"), rs.getInt("quantity")), po.orderId());
        if (stillPending.isEmpty()) return;
        po = stillPending.getFirst();
        BigDecimal totalAmount = executionPrice.multiply(BigDecimal.valueOf(po.quantity())).setScale(2, RoundingMode.HALF_UP);
        long stockId = Math.abs((long) po.stockSymbol().hashCode());

        if ("BUY".equalsIgnoreCase(po.orderType())) {
            // Funds were already held when placing the pending limit order.
            // If executed at a better price, refund the surplus difference!
            BigDecimal initialHeld = po.orderPrice().multiply(BigDecimal.valueOf(po.quantity())).setScale(2, RoundingMode.HALF_UP);
            if (initialHeld.compareTo(totalAmount) > 0) {
                BigDecimal refundDiff = initialHeld.subtract(totalAmount);
                db.update("UPDATE users SET wallet_balance = wallet_balance + ? WHERE user_id = ?", refundDiff, po.userId());
            }
            updateHoldingOnBuy(po.userId(), po.stockSymbol(), po.stockName(), po.quantity(), executionPrice, totalAmount);
        } else {
            // SELL Order Execution
            List<HoldingItem> existing = getHoldingItemsForStock(po.userId(), po.stockSymbol());
            if (existing.isEmpty() || existing.getFirst().quantity() < po.quantity()) {
                db.update("UPDATE orders SET order_status = 'REJECTED' WHERE order_id = ?", po.orderId());
                return;
            }
            db.update("UPDATE users SET wallet_balance = wallet_balance + ? WHERE user_id = ?", totalAmount, po.userId());
            BigDecimal resultingBalance = db.queryForObject("SELECT wallet_balance FROM users WHERE user_id = ?",
                    BigDecimal.class, po.userId());
            if (resultingBalance != null && resultingBalance.compareTo(MAX_WALLET_BALANCE) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wallet balance exceeds the supported limit");
            }
            updateHoldingOnSell(po.userId(), po.stockSymbol(), po.quantity(), existing.getFirst());
        }

        db.update("UPDATE orders SET order_status = 'EXECUTED' WHERE order_id = ?", po.orderId());

        db.update("""
                INSERT INTO transactions (user_id, stock_id, transaction_type, price, quantity, transaction_time)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                po.userId(), stockId, po.orderType(), executionPrice, po.quantity());
    }

    /**
     * Cancels an open PENDING Limit order and refunds any held margin.
     */
    @Transactional
    public Map<String, Object> cancelOrder(long userId, long orderId) {
        List<PendingOrder> list = db.query("""
                SELECT order_id, user_id, stock_symbol, stock_name, order_type, order_price, quantity
                FROM orders
                WHERE order_id = ? AND user_id = ? AND order_status = 'PENDING'
                """,
                (rs, rowNum) -> new PendingOrder(
                        rs.getLong("order_id"),
                        rs.getLong("user_id"),
                        rs.getString("stock_symbol"),
                        rs.getString("stock_name"),
                        rs.getString("order_type"),
                        rs.getBigDecimal("order_price"),
                        rs.getInt("quantity")
                ),
                orderId, userId
        );

        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pending order not found or already completed");
        }

        PendingOrder po = list.getFirst();
        if ("BUY".equalsIgnoreCase(po.orderType())) {
            BigDecimal refund = po.orderPrice().multiply(BigDecimal.valueOf(po.quantity())).setScale(2, RoundingMode.HALF_UP);
            db.update("UPDATE users SET wallet_balance = wallet_balance + ? WHERE user_id = ?", refund, userId);
        }

        db.update("UPDATE orders SET order_status = 'CANCELLED' WHERE order_id = ?", orderId);

        BigDecimal currentBalance = db.queryForObject(
                "SELECT wallet_balance FROM users WHERE user_id = ?",
                BigDecimal.class,
                userId
        );

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("status", "CANCELLED");
        result.put("message", "Order #" + orderId + " has been cancelled.");
        result.put("remainingWalletBalance", currentBalance != null ? currentBalance : BigDecimal.ZERO);
        return result;
    }

    public record PendingOrder(long orderId, long userId, String stockSymbol, String stockName, String orderType, BigDecimal orderPrice, int quantity) { }

    public List<HoldingItem> getHoldings(long userId) {
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid user ID is required");
        }
        return db.query("""
                SELECT holding_id, user_id, stock_symbol, stock_name, quantity, average_purchase_price, invested_amount, updated_at
                FROM holdings
                WHERE user_id = ? AND quantity > 0
                ORDER BY updated_at DESC
                """,
                (rs, rowNum) -> {
                    String sym = rs.getString("stock_symbol");
                    return new HoldingItem(
                            rs.getLong("holding_id"),
                            rs.getLong("user_id"),
                            sym,
                            resolveStockName(sym, rs.getString("stock_name")),
                            rs.getInt("quantity"),
                            rs.getBigDecimal("average_purchase_price"),
                            rs.getBigDecimal("invested_amount"),
                            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : Instant.now()
                    );
                },
                userId
        );
    }

    public List<OrderItem> getOrders(long userId) {
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid user ID is required");
        }
        return db.query("""
                SELECT order_id, user_id, stock_symbol, stock_name, order_type, order_price, quantity, order_status, created_at
                FROM orders
                WHERE user_id = ?
                ORDER BY created_at DESC
                """,
                (rs, rowNum) -> {
                    String sym = rs.getString("stock_symbol");
                    return new OrderItem(
                            rs.getLong("order_id"),
                            rs.getLong("user_id"),
                            sym,
                            resolveStockName(sym, rs.getString("stock_name")),
                            rs.getString("order_type"),
                            rs.getBigDecimal("order_price"),
                            rs.getInt("quantity"),
                            rs.getString("order_status"),
                            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : Instant.now()
                    );
                },
                userId
        );
    }

    public List<WatchlistItem> getWatchlist(long userId) {
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid user ID is required");
        }
        return db.query("""
                SELECT watchlist_id, user_id, stock_symbol, stock_name, added_at
                FROM watchlist
                WHERE user_id = ?
                ORDER BY added_at DESC
                """,
                (rs, rowNum) -> {
                    String sym = rs.getString("stock_symbol");
                    return new WatchlistItem(
                            rs.getLong("watchlist_id"),
                            rs.getLong("user_id"),
                            sym,
                            resolveStockName(sym, rs.getString("stock_name")),
                            rs.getTimestamp("added_at") != null ? rs.getTimestamp("added_at").toInstant() : Instant.now()
                    );
                },
                userId
        );
    }

    public List<String> getPendingStockSymbols() {
        return db.query("SELECT DISTINCT stock_symbol FROM orders WHERE order_status = 'PENDING' ORDER BY stock_symbol",
                (rs, rowNum) -> rs.getString("stock_symbol"));
    }

    public List<WatchlistDataItem> getWatchlistData(long userId) {
        List<WatchlistItem> watchlist = getWatchlist(userId);
        if (watchlist.isEmpty()) return List.of();
        if (watchlist.size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Watchlists are limited to 100 stocks per request");
        }

        Map<String, IndstocksMarketDataService.StockQuote> quotesBySymbol = new HashMap<>();
        try {
            List<IndstocksMarketDataService.StockQuote> quotes = marketDataService.getStockQuotes(
                    watchlist.stream().map(WatchlistItem::stockSymbol).toList());
            for (IndstocksMarketDataService.StockQuote quote : quotes) {
                if (quote != null && quote.stockSymbol() != null) {
                    quotesBySymbol.put(quote.stockSymbol().toUpperCase(Locale.ROOT), quote);
                }
            }
        } catch (Exception exception) {
            // Keep watchlist access available if the quote provider is temporarily unavailable.
        }

        return watchlist.stream().map(item -> {
            IndstocksMarketDataService.StockQuote quote = quotesBySymbol.get(
                    item.stockSymbol().toUpperCase(Locale.ROOT));
            return new WatchlistDataItem(
                    item.watchlistId(), item.userId(), item.stockSymbol(), item.stockName(), item.addedAt(),
                    quote != null ? quote.price() : null,
                    quote != null ? quote.dayChange() : null,
                    quote != null ? quote.dayChangePercentage() : null,
                    quote != null ? quote.exchange() : null,
                    quote != null ? quote.direction() : "UNKNOWN",
                    quote != null ? quote.updatedAt() : item.addedAt());
        }).toList();
    }

    public Map<String, Object> addToWatchlist(long userId, String rawSymbol, String rawName) {
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid user ID is required");
        }
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        String name = resolveStockName(symbol, rawName);

        if (symbol.isBlank() || symbol.length() > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock symbol must be between 1 and 30 characters");
        }

        List<Long> activeUsers = db.query("SELECT user_id FROM users WHERE user_id = ? AND is_active = TRUE",
                (rs, rowNum) -> rs.getLong("user_id"), userId);
        if (activeUsers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active user not found");

        IndstocksMarketDataService.StockQuote quote = marketDataService.getStockQuote(symbol);
        name = quote.stockName();

        db.update("""
                INSERT INTO watchlist (user_id, stock_symbol, stock_name, added_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, stock_symbol) DO NOTHING
                """,
                userId, symbol, name);

        return Map.of("success", true, "message", symbol + " added to watchlist", "symbol", symbol);
    }

    public Map<String, Object> removeFromWatchlist(long userId, String rawSymbol) {
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid user ID is required");
        }
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        db.update("DELETE FROM watchlist WHERE user_id = ? AND stock_symbol = ?", userId, symbol);
        return Map.of("success", true, "message", symbol + " removed from watchlist", "symbol", symbol);
    }

    public record OrderResult(
            long orderId,
            long userId,
            String stockSymbol,
            String stockName,
            String orderType,
            BigDecimal price,
            int quantity,
            BigDecimal totalAmount,
            BigDecimal remainingWalletBalance,
            String orderStatus,
            String message
    ) { }

    public record HoldingItem(
            long holdingId,
            long userId,
            String stockSymbol,
            String stockName,
            int quantity,
            BigDecimal averagePurchasePrice,
            BigDecimal investedAmount,
            Instant updatedAt
    ) { }

    public record OrderItem(
            long orderId,
            long userId,
            String stockSymbol,
            String stockName,
            String orderType,
            BigDecimal orderPrice,
            int quantity,
            String orderStatus,
            Instant createdAt
    ) { }

    public record WatchlistItem(
            long watchlistId,
            long userId,
            String stockSymbol,
            String stockName,
            Instant addedAt
    ) { }

    public record WatchlistDataItem(
            long watchlistId,
            long userId,
            String stockSymbol,
            String stockName,
            Instant addedAt,
            BigDecimal price,
            BigDecimal dayChange,
            BigDecimal dayChangePercentage,
            String exchange,
            String direction,
            Instant quoteUpdatedAt
    ) { }
}
