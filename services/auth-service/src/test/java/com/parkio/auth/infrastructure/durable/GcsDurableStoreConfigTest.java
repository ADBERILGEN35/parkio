package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.durable.TrustedKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The GCS durable store is off by default, refuses half-configuration without leaking secrets, and
 * refuses to start alongside the MinIO object-lock store.
 */
class GcsDurableStoreConfigTest {

    private static final String PREFIX = "parkio.privacy.account-erasure.durable-store.gcs.";
    private static final String DATABASE = "postgresql:7000000000000000008:parkio_auth";
    private static final byte[] SECRET = "gcs-config-test-hmac-key-do-not-print!".getBytes(StandardCharsets.UTF_8);
    private static final TrustedKey KEY = TrustedKey.active("gcs-config-key-2026a", "auth-gcs-config-test", SECRET,
            Instant.parse("2026-01-01T00:00:00Z"));

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GcsDurableStoreConfig.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(JdbcTemplate.class, () -> jdbc);

    @Test
    void noStoreUnlessEnabled() {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(ObjectLockDurableErasureRecordStore.class));
    }

    @Test
    void enabledWithoutOperatorInputsFailsNamingOnlyTheSettings() {
        runner.withPropertyValues(
                        PREFIX + "enabled=true",
                        PREFIX + "retention-mode=forever")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("project-id")
                            .hasMessageContaining("bucket")
                            .hasMessageContaining("retention-mode")
                            .hasMessageContaining("retention (positive duration)")
                            .hasMessageContaining("trust-file")
                            .hasMessageContaining("producer-key-id");
                });
    }

    @Test
    void mutualExclusionWithObjectLockFailsClosed() throws Exception {
        Path file = TrustDocuments.write(DATABASE, KEY);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(DATABASE);
        runner.withPropertyValues(
                        PREFIX + "enabled=true",
                        PREFIX + "project-id=parkio-test",
                        PREFIX + "bucket=parkio-erasure-evidence-test",
                        PREFIX + "retention-mode=GOVERNANCE",
                        PREFIX + "retention=2h",
                        PREFIX + "trust-file=" + file,
                        PREFIX + "producer-key-id=" + KEY.keyId(),
                        "parkio.privacy.account-erasure.durable-store.object-lock.enabled=true")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("cannot both be true")
                        .hasMessageNotContaining(HexFormat.of().formatHex(SECRET)));
        Files.deleteIfExists(file);
    }
}
