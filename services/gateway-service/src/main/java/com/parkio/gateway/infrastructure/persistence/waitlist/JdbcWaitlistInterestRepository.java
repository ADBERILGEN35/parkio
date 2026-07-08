package com.parkio.gateway.infrastructure.persistence.waitlist;

import com.parkio.gateway.application.waitlist.WaitlistExportRow;
import com.parkio.gateway.application.waitlist.WaitlistInterest;
import com.parkio.gateway.application.waitlist.WaitlistInterestRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    public void insertIfAbsent(WaitlistInterest interest) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO waitlist_interest (
                        id, email, email_hash, consent_timestamp, city, role, source,
                        ip_hash, user_agent_hash, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    interest.id(),
                    interest.email(),
                    interest.emailHash(),
                    Timestamp.from(interest.consentTimestamp()),
                    interest.city(),
                    interest.role(),
                    interest.source(),
                    interest.ipHash(),
                    interest.userAgentHash(),
                    Timestamp.from(interest.createdAt()));
        } catch (DuplicateKeyException ignored) {
            // Enumeration-safe duplicate handling: new and existing emails get the same 202 response.
        }
    }

    @Override
    public List<WaitlistExportRow> export(Instant createdFrom, Instant createdTo) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT email, city, role, source, created_at, consent_timestamp
                FROM waitlist_interest
                WHERE 1 = 1
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
}
