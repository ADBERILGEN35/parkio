package com.parkio.media.infrastructure.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

    public static final String HEADER_NAME = "Idempotency-Key";

    private static final int MIN_KEY_LENGTH = 8;
    private static final int MAX_KEY_LENGTH = 128;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration ttl;
    private final boolean postgresql;

    public IdempotencyService(JdbcTemplate jdbc,
                              ObjectMapper objectMapper,
                              Clock clock,
                              @Value("${parkio.idempotency.ttl:24h}") Duration ttl) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ttl = ttl;
        this.postgresql = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)
                connection -> "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }

    @Transactional
    public <T> IdempotentResponse<T> execute(UUID userId,
                                             String method,
                                             String path,
                                             String rawKey,
                                             String fingerprint,
                                             Class<T> responseType,
                                             Supplier<IdempotentResponse<T>> action) {
        String key = validateKey(rawKey);
        Instant now = clock.instant();

        for (int attempt = 0; attempt < 2; attempt++) {
            UUID recordId = UUID.randomUUID();
            int inserted = claim(
                    recordId, userId, method, path, key, fingerprint, now, now.plus(ttl));

            if (inserted == 1) {
                IdempotentResponse<T> response = action.get();
                String responseBody = serialize(response.body());
                jdbc.update("""
                        UPDATE idempotency_records
                           SET status = 'COMPLETED', response_status = ?, response_body = ?
                         WHERE id = ?
                        """, response.statusCode(), responseBody, recordId);
                return response;
            }

            StoredRecord existing = find(userId, method, key);
            if (!existing.expiresAt().isAfter(now)) {
                int deleted = jdbc.update(
                        "DELETE FROM idempotency_records WHERE id = ? AND expires_at <= ?",
                        existing.id(), now);
                if (deleted == 1) {
                    continue;
                }
            }
            if (!existing.path().equals(path) || !existing.fingerprint().equals(fingerprint)) {
                throw conflict();
            }
            if (!"COMPLETED".equals(existing.status())) {
                throw new IdempotencyException(
                        "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                        "A request with this Idempotency-Key is still in progress.");
            }
            return IdempotentResponse.replay(
                    existing.responseStatus(),
                    deserialize(existing.responseBody(), responseType));
        }

        throw new IdempotencyException(
                "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                "A request with this Idempotency-Key is still in progress.");
    }

    private int claim(UUID recordId,
                      UUID userId,
                      String method,
                      String path,
                      String key,
                      String fingerprint,
                      Instant createdAt,
                      Instant expiresAt) {
        if (postgresql) {
            return jdbc.update("""
                    INSERT INTO idempotency_records (
                        id, user_id, http_method, operation_path, idempotency_key,
                        request_fingerprint, status, created_at, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?)
                    ON CONFLICT (user_id, http_method, idempotency_key) DO NOTHING
                    """, recordId, userId, method, path, key, fingerprint, createdAt, expiresAt);
        }
        return jdbc.update("""
                INSERT INTO idempotency_records (
                    id, user_id, http_method, operation_path, idempotency_key,
                    request_fingerprint, status, created_at, expires_at
                )
                SELECT ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?
                 WHERE NOT EXISTS (
                    SELECT 1 FROM idempotency_records
                     WHERE user_id = ? AND http_method = ? AND idempotency_key = ?
                 )
                """, recordId, userId, method, path, key, fingerprint, createdAt, expiresAt,
                userId, method, key);
    }

    private StoredRecord find(UUID userId, String method, String key) {
        return jdbc.queryForObject("""
                SELECT id, operation_path, request_fingerprint, status,
                       response_status, response_body, expires_at
                  FROM idempotency_records
                 WHERE user_id = ? AND http_method = ? AND idempotency_key = ?
                """, (rs, rowNum) -> new StoredRecord(
                rs.getObject("id", UUID.class),
                rs.getString("operation_path"),
                rs.getString("request_fingerprint"),
                rs.getString("status"),
                rs.getInt("response_status"),
                rs.getString("response_body"),
                rs.getObject("expires_at", Instant.class)),
                userId, method, key);
    }

    private String validateKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new IdempotencyException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header is required.");
        }
        String key = rawKey.trim();
        if (key.length() < MIN_KEY_LENGTH || key.length() > MAX_KEY_LENGTH) {
            throw new IdempotencyException(
                    "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must be between 8 and 128 characters.");
        }
        return key;
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to store idempotent response", ex);
        }
    }

    private <T> T deserialize(String body, Class<T> responseType) {
        try {
            return objectMapper.readValue(body, responseType);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to replay idempotent response", ex);
        }
    }

    private static IdempotencyException conflict() {
        return new IdempotencyException(
                "IDEMPOTENCY_KEY_CONFLICT",
                "Idempotency-Key was already used for a different request.");
    }

    private record StoredRecord(
            UUID id,
            String path,
            String fingerprint,
            String status,
            int responseStatus,
            String responseBody,
            Instant expiresAt) {
    }
}
