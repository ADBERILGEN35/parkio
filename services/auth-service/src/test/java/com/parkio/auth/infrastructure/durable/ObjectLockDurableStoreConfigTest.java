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
 * The object-lock store is off by default and refuses to start half-configured, with an invalid
 * trust document, with a signing key that is missing or may not sign now, or with a trust
 * document pinned to another database. Errors never contain a secret.
 */
class ObjectLockDurableStoreConfigTest {

    private static final String PREFIX = "parkio.privacy.account-erasure.durable-store.object-lock.";
    private static final String DATABASE = "postgresql:7000000000000000005:parkio_auth";
    private static final byte[] SECRET = "config-test-hmac-key-do-not-print-this".getBytes(StandardCharsets.UTF_8);
    private static final TrustedKey KEY = TrustedKey.active("config-test-key-2026a", "auth-config-test", SECRET,
            Instant.parse("2026-01-01T00:00:00Z"));

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ObjectLockDurableStoreConfig.class)
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
                        PREFIX + "secret-key=do-not-print-this-secret",
                        PREFIX + "retention-mode=forever")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("endpoint")
                            .hasMessageContaining("bucket")
                            .hasMessageContaining("access-key")
                            .hasMessageContaining("retention-mode")
                            .hasMessageContaining("retention (positive duration)")
                            .hasMessageContaining("trust-file")
                            .hasMessageContaining("producer-key-id")
                            .hasMessageNotContaining("do-not-print-this-secret");
                });
    }

    @Test
    void anInvalidTrustDocumentStopsStartupWithoutPrintingIt() throws Exception {
        Path file = Files.createTempFile("parkio-erasure-trust-invalid-", ".json");
        Files.writeString(file, "{\"format\":\"parkio-erasure-evidence-trust\",\"version\":1,\"databaseIdentity\":\""
                + DATABASE + "\",\"keys\":[{\"keyId\":\"short\",\"producerId\":\"auth\",\"notBefore\":"
                + "\"2026-01-01T00:00:00Z\",\"keyHex\":\"" + HexFormat.of().formatHex("tooshort".getBytes(StandardCharsets.UTF_8)) + "\"}]}");
        try {
            enabled(file, "short").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .hasMessage("parkio.privacy.account-erasure.durable-store.object-lock.trust-file is invalid: "
                                + "trust key short must be at least 32 bytes")
                        .hasMessageNotContaining(HexFormat.of().formatHex("tooshort".getBytes(StandardCharsets.UTF_8)));
            });
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aSigningKeyThatIsMissingOrMayNotSignNowStopsStartup() {
        Path file = TrustDocuments.write(DATABASE, KEY,
                new TrustedKey("config-test-key-2025", "auth-config-test", SECRET,
                        Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-12-31T00:00:00Z"), false));
        enabled(file, "no-such-key").run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("producer-key-id no-such-key is not in the trust document"));
        enabled(file, "config-test-key-2025").run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("producer key config-test-key-2025 is retired or outside its signing window")
                .hasMessageNotContaining(HexFormat.of().formatHex(SECRET)));
    }

    @Test
    void aTrustDocumentPinnedToAnotherDatabaseStopsStartup() {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("postgresql:7000000000000000006:parkio_auth");
        enabled(TrustDocuments.write(DATABASE, KEY), KEY.keyId()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("the trust document is pinned to " + DATABASE)
                    .hasMessageContaining("this service uses postgresql:7000000000000000006:parkio_auth")
                    .hasMessageNotContaining(HexFormat.of().formatHex(SECRET));
        });
    }

    private ApplicationContextRunner enabled(Path trustFile, String keyId) {
        return runner.withPropertyValues(
                PREFIX + "enabled=true",
                PREFIX + "endpoint=http://127.0.0.1:9",
                PREFIX + "bucket=parkio-erasure-evidence",
                PREFIX + "access-key=test-access",
                PREFIX + "secret-key=test-secret",
                PREFIX + "retention-mode=GOVERNANCE",
                PREFIX + "retention=1d",
                PREFIX + "trust-file=" + trustFile,
                PREFIX + "producer-key-id=" + keyId);
    }
}
