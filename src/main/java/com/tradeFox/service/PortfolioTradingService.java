package com.tradeFox.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PortfolioTradingService {
    private final JdbcTemplate db;
    private final IndstocksMarketDataService marketDataService;

    public PortfolioTradingService(JdbcTemplate db, IndstocksMarketDataService marketDataService) {
        this.db = db;
        this.marketDataService = marketDataService;
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
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid user ID is required");
        }
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        String name = rawName == null || rawName.isBlank() ? symbol : rawName.trim();
        String orderType = rawOrderType == null ? "" : rawOrderType.trim().toUpperCase(Locale.ROOT);
        String executionType = (rawExecutionType == null || rawExecutionType.isBlank())
                ? "MARKET" : rawExecutionType.trim().toUpperCase(Locale.ROOT);

        if (symbol.isBlank() || symbol.length() > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock symbol must be between 1 and 30 characters");
        }
        if (!"BUY".equals(orderType) && !"SELL".equals(orderType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order type must be either BUY or SELL");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order price must be positive");
        }
        if (quantity <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be greater than zero");
        }

        // Fetch user wallet balance
        List<BigDecimal> balances = db.query(
                "SELECT wallet_balance FROM users WHERE user_id = ? AND is_active = TRUE",
                (rs, rowNum) -> rs.getBigDecimal("wallet_balance"),
                userId
        );
        if (balances.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active user not found");
        }
        BigDecimal currentBalance = balances.getFirst();
        BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);

        BigDecimal effectiveMarketPrice = (currentMarketPrice != null && currentMarketPrice.compareTo(BigDecimal.ZERO) > 0)
                ? currentMarketPrice : price;

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
                db.update("UPDATE users SET wallet_balance = ? WHERE user_id = ?", newBalance, userId);
                updateHoldingOnSell(userId, symbol, quantity, existingHoldings.getFirst());
            }
        }

        String finalStatus = shouldRemainPending ? "PENDING" : "EXECUTED";

        // Insert Order Record
        Long orderId = db.queryForObject("""
                INSERT INTO orders (user_id, stock_symbol, stock_name, order_type, order_price, quantity, order_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                RETURNING order_id
                """,
                Long.class,
                userId, symbol, name, orderType, price.setScale(2, RoundingMode.HALF_UP), quantity, finalStatus
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
            int updatedQty = current.quantity() + quantity;
            BigDecimal updatedInvested = current.investedAmount().add(totalAmount).setScale(2, RoundingMode.HALF_UP);
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
                (rs, rowNum) -> new OrderItem(
                        rs.getLong("order_id"),
                        rs.getLong("user_id"),
                        rs.getString("stock_symbol"),
                        rs.getString("stock_name"),
                        rs.getString("order_type"),
                        rs.getBigDecimal("order_price"),
                        rs.getInt("quantity"),
                        rs.getString("order_status"),
                        rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : Instant.now()
                ),
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
                (rs, rowNum) -> new WatchlistItem(
                        rs.getLong("watchlist_id"),
                        rs.getLong("user_id"),
                        rs.getString("stock_symbol"),
                        rs.getString("stock_name"),
                        rs.getTimestamp("added_at") != null ? rs.getTimestamp("added_at").toInstant() : Instant.now()
                ),
                userId
        );
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
            if (quotes != null) {
                for (IndstocksMarketDataService.StockQuote quote : quotes) {
                    if (quote != null && quote.stockSymbol() != null) {
                        quotesBySymbol.put(quote.stockSymbol().toUpperCase(Locale.ROOT), quote);
                    }
                }
            }
        } catch (Exception ex) {
            // Log and fall back gracefully so user watchlist always loads without 503
        }

        return watchlist.stream().map(item -> {
            IndstocksMarketDataService.StockQuote quote = quotesBySymbol.get(
                    item.stockSymbol().toUpperCase(Locale.ROOT));
            return new WatchlistDataItem(
                    item.watchlistId(),
                    item.userId(),
                    item.stockSymbol(),
                    item.stockName(),
                    item.addedAt(),
                    quote != null ? quote.price() : null,
                    quote != null ? quote.dayChange() : null,
                    quote != null ? quote.dayChangePercentage() : null,
                    quote != null ? quote.exchange() : "NSE",
                    quote != null ? quote.direction() : "UP",
                    quote != null ? quote.updatedAt() : item.addedAt()
            );
        }).toList();
    }

    public Map<String, Object> addToWatchlist(long userId, String rawSymbol, String rawName) {
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid user ID is required");
        }
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        String name = rawName == null || rawName.isBlank() ? symbol : rawName.trim();

        if (symbol.isBlank() || symbol.length() > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock symbol must be between 1 and 30 characters");
        }

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
