package com.tradeFox.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class UserService {
    private static final BigDecimal INITIAL_WALLET_BALANCE = new BigDecimal("1000000.00");
    private static final BigDecimal MAX_WALLET_BALANCE = new BigDecimal("9999999999999.99");
    private final JdbcTemplate db;

    public UserService(JdbcTemplate db) {
        this.db = db;
    }

    public UserDetails getUserDetails(long userId) {
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be positive");
        }
        List<UserDetails> users = db.query("""
                SELECT user_id, name, email, wallet_balance
                FROM users
                WHERE user_id = ? AND is_active = TRUE
                """, (rs, rowNum) -> new UserDetails(
                rs.getLong("user_id"), rs.getString("name"), rs.getString("email"),
                rs.getBigDecimal("wallet_balance")), userId);
        if (users.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return users.getFirst();
    }

    @Transactional
    public UserDetails addVirtualFunds(long userId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be greater than zero");
        }
        BigDecimal creditedAmount = amount.setScale(2, RoundingMode.HALF_UP);
        if (creditedAmount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be at least 0.01");
        }

        BigDecimal balance = lockWalletBalance(userId);
        BigDecimal updatedBalance = balance.add(creditedAmount);
        if (updatedBalance.compareTo(MAX_WALLET_BALANCE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wallet balance exceeds the supported limit");
        }
        db.update("UPDATE users SET wallet_balance = ? WHERE user_id = ?", updatedBalance, userId);
        return getUserDetails(userId);
    }

    @Transactional
    public UserDetails resetWallet(long userId) {
        lockWalletBalance(userId);

        Integer openHoldings = db.queryForObject(
                "SELECT COUNT(*) FROM holdings WHERE user_id = ? AND quantity > 0", Integer.class, userId);
        Integer pendingOrders = db.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ? AND order_status = 'PENDING'", Integer.class, userId);
        if ((openHoldings != null && openHoldings > 0) || (pendingOrders != null && pendingOrders > 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Close open holdings and cancel pending orders before resetting the wallet");
        }

        db.update("UPDATE users SET wallet_balance = ? WHERE user_id = ?", INITIAL_WALLET_BALANCE, userId);
        return getUserDetails(userId);
    }

    private BigDecimal lockWalletBalance(long userId) {
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be positive");
        }
        List<BigDecimal> balances = db.query("""
                SELECT wallet_balance FROM users
                WHERE user_id = ? AND is_active = TRUE
                FOR UPDATE
                """, (rs, rowNum) -> rs.getBigDecimal("wallet_balance"), userId);
        if (balances.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return balances.getFirst();
    }

    public record UserDetails(long userId, String name, String email, BigDecimal walletBalance) { }
}
