package com.parkio.gateway.infrastructure.persistence.waitlist;

import com.parkio.gateway.application.waitlist.WaitlistExportRow;
import com.parkio.gateway.application.waitlist.WaitlistInterest;
import com.parkio.gateway.application.waitlist.WaitlistInterestRepository;
import com.parkio.gateway.application.waitlist.WaitlistStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWaitlistInterestRepository implements WaitlistInterestRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcWaitlistInterestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insertPendingIfAbsent(WaitlistInterest interest) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO waitlist_interest (
                        id, email, email_hash, consent_timestamp, client_consent_timestamp, city, role, source, locale,
                        status, verification_token_hash, withdraw_token_hash, verification_expires_at,
                        verification_sent_at, resend_count, confirmed_at, withdrawn_at,
                        ip_hash, user_agent_hash, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    interest.id(),
                    interest.email(),
                    interest.emailHash(),
                    Timestamp.from(interest.consentTimestamp()),
                    toTimestamp(interest.clientConsentTimestamp()),
                    interest.city(),
                    interest.role(),
                    interest.source(),
                    interest.locale(),
                    interest.status().name(),
                    interest.verificationTokenHash(),
                    interest.withdrawTokenHash(),
                    toTimestamp(interest.verificationExpiresAt()),
                    toTimestamp(interest.verificationSentAt()),
                    interest.resendCount(),
                    toTimestamp(interest.confirmedAt()),
                    toTimestamp(interest.withdrawnAt()),
                    interest.ipHash(),
                    interest.userAgentHash(),
                    Timestamp.from(interest.createdAt()));
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    @Override
    public Optional<WaitlistInterest> findByEmailHash(String emailHash) {
        return queryOne("SELECT * FROM waitlist_interest WHERE email_hash = ?", emailHash);
    }

    @Override
    public Optional<WaitlistInterest> findByVerificationTokenHash(String tokenHash) {
        return queryOne("SELECT * FROM waitlist_interest WHERE verification_token_hash = ?", tokenHash);
    }

    @Override
    public Optional<WaitlistInterest> findByWithdrawTokenHash(String tokenHash) {
        return queryOne("SELECT * FROM waitlist_interest WHERE withdraw_token_hash = ?", tokenHash);
    }

    @Override
    public boolean confirmByTokenHash(String tokenHash, Instant now) {
        // Keep verification_token_hash after confirm so a second POST (scanner-safe button
        // retry) is idempotent; GET confirm remains unimplemented on the controller.
        int updated = jdbcTemplate.update("""
                UPDATE waitlist_interest
                SET status = 'CONFIRMED',
                    confirmed_at = COALESCE(confirmed_at, ?)
                WHERE verification_token_hash = ?
                  AND status = 'PENDING'
                  AND verification_expires_at IS NOT NULL
                  AND verification_expires_at > ?
                """,
                Timestamp.from(now),
                tokenHash,
                Timestamp.from(now));
        return updated > 0;
    }

    @Override
    public boolean withdrawByTokenHash(String tokenHash, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE waitlist_interest
                SET status = 'WITHDRAWN',
                    withdrawn_at = ?,
                    email = CONCAT('withdrawn-', REPLACE(CAST(id AS VARCHAR), '-', ''), '@invalid.local'),
                    email_hash = CONCAT('withdrawn-', REPLACE(CAST(id AS VARCHAR), '-', '')),
                    verification_token_hash = NULL,
                    withdraw_token_hash = NULL,
                    verification_expires_at = NULL
                WHERE withdraw_token_hash = ?
                  AND status IN ('PENDING', 'CONFIRMED')
                """,
                Timestamp.from(now),
                tokenHash);
        return updated > 0;
    }

    @Override
    public boolean refreshPendingVerification(
            String emailHash,
            String verificationTokenHash,
            String withdrawTokenHash,
            Instant expiresAt,
            Instant sentAt,
            int resendCount) {
        int updated = jdbcTemplate.update("""
                UPDATE waitlist_interest
                SET verification_token_hash = ?,
                    withdraw_token_hash = ?,
                    verification_expires_at = ?,
                    verification_sent_at = ?,
                    resend_count = ?
                WHERE email_hash = ?
                  AND status = 'PENDING'
                """,
                verificationTokenHash,
                withdrawTokenHash,
                Timestamp.from(expiresAt),
                toTimestamp(sentAt),
                resendCount,
                emailHash);
        return updated > 0;
    }

    @Override
    public void markVerificationSent(String emailHash, Instant sentAt, int resendCount) {
        jdbcTemplate.update("""
                UPDATE waitlist_interest
                SET verification_sent_at = ?,
                    resend_count = ?
                WHERE email_hash = ?
                  AND status = 'PENDING'
                """,
                Timestamp.from(sentAt),
                resendCount,
                emailHash);
    }

    @Override
    public List<WaitlistExportRow> exportConfirmed(Instant createdFrom, Instant createdTo) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT email, city, role, source, created_at, consent_timestamp
                FROM waitlist_interest
                WHERE status = 'CONFIRMED'
                """);
        if (createdFrom != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(createdFrom));
        }
        if (createdTo != null) {
            sql.append(" AND created_at < ?");
            args.add(Timestamp.from(createdTo));
        }
        sql.append(" ORDER BY created_at ASC");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new WaitlistExportRow(
                rs.getString("email"),
                rs.getString("city"),
                rs.getString("role"),
                rs.getString("source"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("consent_timestamp").toInstant()), args.toArray());
    }

    private Optional<WaitlistInterest> queryOne(String sql, String arg) {
        List<WaitlistInterest> rows = jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), arg);
        return rows.stream().findFirst();
    }

    private static WaitlistInterest mapRow(ResultSet rs) throws SQLException {
        return new WaitlistInterest(
                UUID.fromString(rs.getString("id")),
                rs.getString("email"),
                rs.getString("email_hash"),
                rs.getTimestamp("consent_timestamp").toInstant(),
                toInstant(rs.getTimestamp("client_consent_timestamp")),
                rs.getString("city"),
                rs.getString("role"),
                rs.getString("source"),
                rs.getString("locale"),
                WaitlistStatus.valueOf(rs.getString("status")),
                rs.getString("verification_token_hash"),
                rs.getString("withdraw_token_hash"),
                toInstant(rs.getTimestamp("verification_expires_at")),
                toInstant(rs.getTimestamp("verification_sent_at")),
                rs.getInt("resend_count"),
                toInstant(rs.getTimestamp("confirmed_at")),
                toInstant(rs.getTimestamp("withdrawn_at")),
                rs.getString("ip_hash"),
                rs.getString("user_agent_hash"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
