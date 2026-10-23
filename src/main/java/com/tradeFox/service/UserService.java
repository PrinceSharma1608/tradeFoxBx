package com.tradeFox.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
public class UserService {
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

    public record UserDetails(long userId, String name, String email, BigDecimal walletBalance) { }
}
