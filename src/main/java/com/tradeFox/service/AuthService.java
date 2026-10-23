package com.tradeFox.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

@Service
public class AuthService {
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate db;
    private final String jwtSecret;
    private final long tokenLifetimeMs;

    public AuthService(JdbcTemplate db,
                       @Value("${app.jwt.secret}") String jwtSecret,
                       @Value("${app.jwt.expiration-ms}") long tokenLifetimeMs) {
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32 || tokenLifetimeMs <= 0) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes and expiry must be positive");
        }
        this.db = db;
        this.jwtSecret = jwtSecret;
        this.tokenLifetimeMs = tokenLifetimeMs;
    }

    public Map<String, Object> signup(String rawName, String rawEmail, String password) {
        String name = rawName == null ? "" : rawName.trim();
        String email = normalizeEmail(rawEmail);
        if (name.isBlank() || name.length() > 100 || !validEmail(email)
                || password == null || password.length() < 8 || password.length() > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provide a name, valid email, and password between 8 and 72 characters");
        }
        try {
            Map<String, Object> user = db.queryForMap("""
                    INSERT INTO users (name, email, password_hash)
                    VALUES (?, ?, ?)
                    RETURNING user_id, name, email
                    """, name, email, hashPassword(password));
            return authResult(user);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }
    }

    public Map<String, Object> login(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        if (email.isBlank() || password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and password are required");
        }
        if (!validEmail(email) || password.length() > 72) {
            throw invalidCredentials();
        }

        var users = db.query("""
                SELECT user_id, name, email, password_hash, is_active
                FROM users WHERE email = ?
                """, (rs, row) -> Map.<String, Object>of(
                "user_id", rs.getLong("user_id"), "name", rs.getString("name"),
                "email", rs.getString("email"), "password_hash", rs.getString("password_hash"),
                "is_active", rs.getBoolean("is_active")), email);

        if (users.isEmpty()) throw invalidCredentials();
        Map<String, Object> user = users.getFirst();
        if (!(Boolean) user.get("is_active")
                || !passwordMatches(password, (String) user.get("password_hash"))) {
            throw invalidCredentials();
        }
        return authResult(user);
    }

    /** Verifies a signed, unexpired token and returns its active account ID. */
    public Long userIdForToken(String token) {
        if (token == null || token.length() > 4096) return null;
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) return null;

        final String email;
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            JsonNode header = JSON.readTree(decoder.decode(parts[0]));
            JsonNode claims = JSON.readTree(decoder.decode(parts[1]));
            if (!"HS256".equals(header.path("alg").asText())) return null;
            JsonNode subject = claims.get("sub");
            JsonNode expiry = claims.get("exp");
            if (subject == null || !subject.isTextual() || subject.asText().isBlank()
                    || expiry == null || !expiry.canConvertToLong()
                    || expiry.asLong() <= Instant.now().getEpochSecond()) return null;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            byte[] actual = decoder.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) return null;
            email = normalizeEmail(subject.asText());
        } catch (Exception exception) {
            return null;
        }

        var userIds = db.query("SELECT user_id FROM users WHERE email = ? AND is_active = TRUE",
                (rs, rowNum) -> rs.getLong("user_id"), email);
        return userIds.isEmpty() ? null : userIds.getFirst();
    }

    private Map<String, Object> authResult(Map<String, Object> user) {
        Map<String, Object> publicUser = Map.of(
                "user_id", user.get("user_id"),
                "name", user.get("name"),
                "email", user.get("email"));
        return Map.of("token", createToken((String) user.get("email")), "user", publicUser);
    }

    private String createToken(String email) {
        try {
            Instant now = Instant.now();
            long issuedAt = now.getEpochSecond();
            long expiresAt = now.plusMillis(tokenLifetimeMs).getEpochSecond();
            Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
            String header = encode(base64, "{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String payload = encode(base64, "{\"sub\":\"" + jsonEscape(email) + "\",\"iat\":"
                    + issuedAt + ",\"exp\":" + expiresAt + "}");
            String unsignedToken = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8));
            return unsignedToken + "." + base64.encodeToString(signature);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create JWT", exception);
        }
    }

    private String encode(Base64.Encoder encoder, String value) {
        return encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derivePasswordHash(password, salt, PASSWORD_ITERATIONS);
        return "pbkdf2$" + PASSWORD_ITERATIONS + "$" + HexFormat.of().formatHex(salt)
                + "$" + HexFormat.of().formatHex(hash);
    }

    private boolean passwordMatches(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 4 || !parts[0].equals("pbkdf2")) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = HexFormat.of().parseHex(parts[2]);
            byte[] expected = HexFormat.of().parseHex(parts[3]);
            byte[] actual = derivePasswordHash(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] derivePasswordHash(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash password", exception);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean validEmail(String email) {
        return email.length() <= 255 && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }
}
