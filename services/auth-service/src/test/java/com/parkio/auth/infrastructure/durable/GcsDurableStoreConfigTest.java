package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.domain.exception.AuthException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
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
    void listBoundsDefaultToTheAdapterDefaultsAndMustBePositive() {
        GcsDurableStoreProperties defaults = new GcsDurableStoreProperties();
        assertThat(new GcsObjectRetentionBucket.ListBounds(defaults.getListMaxPages(), defaults.getListDeadline()))
                .isEqualTo(GcsObjectRetentionBucket.ListBounds.DEFAULT);

        runner.withPropertyValues(
                        PREFIX + "enabled=true",
                        PREFIX + "list-max-pages=0",
                        PREFIX + "list-deadline=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("list-max-pages (at least 1)")
                            .hasMessageContaining("list-deadline (positive duration)");
                });
    }

    /** #616 F3: a bare number is seconds, as the default is written ("30s"); a unit still wins. */
    @Test
    void aBareListDeadlineIsSeconds() {
        assertThat(bind("list-deadline", "30").getListDeadline()).isEqualTo(Duration.ofSeconds(30));
        assertThat(bind("list-deadline", "1500ms").getListDeadline()).isEqualTo(Duration.ofMillis(1_500));
        assertThat(bind("list-deadline", "PT2M").getListDeadline()).isEqualTo(Duration.ofMinutes(2));
    }

    /** #616 F4: values above the upper limits stop startup, so they cannot switch the bounds off. */
    @Test
    void listBoundsAboveTheirUpperLimitsStopStartup() {
        runner.withPropertyValues(
                        PREFIX + "enabled=true",
                        PREFIX + "list-max-pages=2147483647",
                        PREFIX + "list-deadline=24h")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("list-max-pages (at most 10000)")
                            .hasMessageContaining("list-deadline (at most PT5M)");
                });
        assertThat(bind("list-max-pages", "10000", "list-deadline", "5m").problems())
                .noneMatch(problem -> problem.startsWith("list-"));
        assertThat(bind("list-max-pages", "10001", "list-deadline", "301").problems())
                .contains("list-max-pages (at most 10000)", "list-deadline (at most PT5M)");
    }

    /**
     * #616 F5: the bounds the bean configures are the bound properties, not {@link
     * GcsObjectRetentionBucket.ListBounds#DEFAULT}: with at most two pages, a three-page listing fails closed.
     */
    @Test
    void theConfiguredListBoundsReachTheAdapter() {
        InMemoryGcsObjectRetentionTransport transport = new InMemoryGcsObjectRetentionTransport("parkio-config-test");
        transport.setForcedPageSizeOverride(1);
        for (int n = 1; n <= 3; n++) {
            transport.createGeneration("records/" + n + ".json", new byte[] {'{', '}'}, EvidenceRetentionMode.GOVERNANCE,
                    Instant.parse("2026-12-01T00:00:00Z"), OptionalLong.of(0));
        }

        GcsObjectRetentionBucket bucket = GcsDurableStoreConfig.bucket(
                bind("list-max-pages", "2", "list-deadline", "45"), transport);

        assertThat(bucket.listBounds()).isEqualTo(new GcsObjectRetentionBucket.ListBounds(2, Duration.ofSeconds(45)));
        assertThatThrownBy(() -> bucket.keys("records/"))
                .isInstanceOf(AuthException.class)
                .hasMessage("durable store list records/ failed: more than 2 pages");
        assertThat(GcsDurableStoreConfig.bucket(bind(), transport).listBounds())
                .isEqualTo(GcsObjectRetentionBucket.ListBounds.DEFAULT);
    }

    /**
     * #616 F5: the defaults written in application.yml are {@link GcsObjectRetentionBucket.ListBounds#DEFAULT},
     * so a later change to one cannot be overridden by the other in production. The file is read from
     * the source tree: the test classpath has an application.yml of its own.
     */
    @Test
    void theApplicationYmlDefaultsAreTheAdapterDefaults() throws IOException {
        Resource yml = new FileSystemResource("src/main/resources/application.yml");
        assertThat(yml.exists()).as("auth-service application.yml, read from the module directory").isTrue();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application.yml", yml);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));

        GcsDurableStoreProperties defaults = binder.bindOrCreate(PREFIX.substring(0, PREFIX.length() - 1),
                GcsDurableStoreProperties.class);

        assertThat(defaults.listBounds()).isEqualTo(GcsObjectRetentionBucket.ListBounds.DEFAULT);
        assertThat(new GcsDurableStoreProperties().listBounds()).isEqualTo(GcsObjectRetentionBucket.ListBounds.DEFAULT);
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

    private static GcsDurableStoreProperties bind(String... namesAndValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put(PREFIX + namesAndValues[i], namesAndValues[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate(PREFIX.substring(0, PREFIX.length() - 1), GcsDurableStoreProperties.class);
    }
}
