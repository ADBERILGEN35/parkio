package com.parkio.moderation.infrastructure.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RetentionCleanupJobBindingTest {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");

    @Test
    void bindsBothTimestamptzCutoffsAsJdbcTimestamps() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        RetentionCleanupJob job = new RetentionCleanupJob(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), true, true,
                Duration.ofDays(7), Duration.ofDays(30), 37);

        job.cleanup();

        assertThat(jdbc.invocations).hasSize(2);
        assertThat(jdbc.invocations.get(0).sql()).contains("published = true", "created_at < ?");
        assertThat(jdbc.invocations.get(0).arguments())
                .containsExactly(Timestamp.from(NOW.minus(Duration.ofDays(7))), 37);
        assertThat(jdbc.invocations.get(1).sql()).contains("processed_at < ?");
        assertThat(jdbc.invocations.get(1).arguments())
                .containsExactly(Timestamp.from(NOW.minus(Duration.ofDays(30))), 37);
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private final List<Invocation> invocations = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            invocations.add(new Invocation(sql, args));
            return 0;
        }
    }

    private record Invocation(String sql, Object[] arguments) {
    }
}
