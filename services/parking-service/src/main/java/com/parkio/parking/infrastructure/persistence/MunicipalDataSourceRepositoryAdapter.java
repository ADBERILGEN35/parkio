package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MunicipalDataSourceRepositoryAdapter implements MunicipalDataSourceRepository {
    private final JdbcClient jdbc;

    public MunicipalDataSourceRepositoryAdapter(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Source requireBySourceKey(String sourceKey) {
        return findBySourceKey(sourceKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown municipal source: " + sourceKey));
    }

    @Override
    public Optional<Source> findBySourceKey(String sourceKey) {
        return jdbc.sql("""
                SELECT id, source_key, publisher, attribution_text,
                       aging_after_seconds, stale_after_seconds, last_successful_sync_at
                FROM municipal_data_sources WHERE source_key = :key AND active = true
                """).param("key", sourceKey).query((rs, row) -> {
            Timestamp last = rs.getTimestamp("last_successful_sync_at");
            return new Source(
                    rs.getObject("id", UUID.class), rs.getString("source_key"), rs.getString("publisher"),
                    rs.getString("attribution_text"), rs.getLong("aging_after_seconds"),
                    rs.getLong("stale_after_seconds"),
                    last == null ? null : last.toInstant());
        }).optional();
    }

    @Override
    public void markSuccessful(UUID sourceId, Instant completedAt) {
        jdbc.sql("UPDATE municipal_data_sources SET last_successful_sync_at=:at, updated_at=:at WHERE id=:id")
                .param("at", Timestamp.from(completedAt)).param("id", sourceId).update();
    }
}