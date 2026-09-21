package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.parkio.parking.application.IzelmanImportApplicationService;
import com.parkio.parking.application.IzelmanImportResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.externalsource.izelman.SourceAgeClassification;
import com.parkio.parking.testsupport.PostgisTestImages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Isolated dry-run against official Bizizmir İZELMAN CSVs (Nov 2022), when present.
 *
 * <p>Set {@code PARKIO_IZELMAN_OFFICIAL_DIR} to a directory containing
 * {@code izelman-*-parking*.csv} named exactly as {@link IzelmanSourceKeys}, or place files under
 * {@code agent-tools/parkio-izmir-coverage-expansion-01/sources/izelman/parkio-named}.
 *
 * <p>Publication flags stay false. No occupancy writes. Skips when files are absent.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class OfficialIzelmanCsvIsolatedDryRunIT {
    private static final DockerImageName POSTGIS = PostgisTestImages.dockerImageName();
    private static final Path OFFICIAL_DIR = resolveOfficialDir();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS)
            .withDatabaseName("parkio_izelman_official_dry")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired IzelmanImportApplicationService importService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.kafka.ai-validation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.moderation-timeout.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("parkio.municipal.enabled", () -> "true");
        registry.add("parkio.municipal.izelman.enabled", () -> "true");
        registry.add("parkio.municipal.izelman.facility-import-enabled", () -> "true");
        registry.add("parkio.municipal.izelman.roadside-import-enabled", () -> "true");
        registry.add("parkio.municipal.izelman.tariff-import-enabled", () -> "false");
        registry.add("parkio.municipal.izelman.facility-publication-enabled", () -> "false");
        registry.add("parkio.municipal.izelman.roadside-publication-enabled", () -> "false");
        registry.add("parkio.municipal.izelman.tariff-publication-enabled", () -> "false");
        registry.add("parkio.municipal.izelman.allowed-input-dir", () -> OFFICIAL_DIR.toString());
    }

    @Test
    void dryRunOfficialFacilityAndRoadsideSourcesWithoutDbWrites() {
        assumeTrue(OFFICIAL_DIR != null && Files.isDirectory(OFFICIAL_DIR), "official IZELMAN dir missing");

        Map<String, IzelmanImportResult> results = new LinkedHashMap<>();
        for (String key : new String[] {
            IzelmanSourceKeys.OPEN, IzelmanSourceKeys.CLOSED, IzelmanSourceKeys.BARRIER, IzelmanSourceKeys.ROADSIDE
        }) {
            assumeTrue(Files.isRegularFile(OFFICIAL_DIR.resolve(key + ".csv")), "missing " + key + ".csv");
            IzelmanImportResult dry = importService.importConfigured(key, true);
            results.put(key, dry);
            assertThat(dry.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
            assertThat(dry.dryRun()).isTrue();
            assertThat(dry.accepted()).isPositive();
            assertThat(dry.ageClassification()).isEqualTo(SourceAgeClassification.HISTORICAL);
            assertThat(dry.inserted() + dry.updated()).isZero();
        }

        // Evidence line for coverage expansion package (not unique facilities).
        System.out.println("official_izelman_dry_run_counts");
        results.forEach((key, r) -> System.out.printf(
                "source=%s raw=%d accepted=%d rejected=%d duplicates=%d%n",
                key, r.recordsRead(), r.accepted(), r.rejected(), r.trueDuplicates()));
    }

    @Test
    void isolatedNonDryImportIsIdempotentWithPublicationOff() {
        assumeTrue(OFFICIAL_DIR != null && Files.isDirectory(OFFICIAL_DIR), "official IZELMAN dir missing");
        assumeTrue(Files.isRegularFile(OFFICIAL_DIR.resolve(IzelmanSourceKeys.CLOSED + ".csv")));

        IzelmanImportResult first = importService.importConfigured(IzelmanSourceKeys.CLOSED, false);
        assertThat(first.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(first.dryRun()).isFalse();
        assertThat(first.accepted()).isEqualTo(23);
        assertThat(first.inserted()).isEqualTo(23);

        IzelmanImportResult second = importService.importConfigured(IzelmanSourceKeys.CLOSED, false);
        assertThat(second.status()).isEqualTo(MunicipalSyncRunStatus.SUCCESS);
        assertThat(second.accepted()).isEqualTo(23);
        assertThat(second.inserted()).isZero();
        assertThat(second.unchanged() + second.updated()).isEqualTo(23);

        System.out.printf(
                "official_izelman_isolated_import closed first_inserted=%d second_unchanged_or_updated=%d%n",
                first.inserted(),
                second.unchanged() + second.updated());
    }

    private static Path resolveOfficialDir() {
        String env = System.getenv("PARKIO_IZELMAN_OFFICIAL_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        Path[] candidates = {
            Path.of("agent-tools/parkio-izmir-coverage-expansion-01/sources/izelman/parkio-named")
                    .toAbsolutePath()
                    .normalize(),
            Path.of("../agent-tools/parkio-izmir-coverage-expansion-01/sources/izelman/parkio-named")
                    .toAbsolutePath()
                    .normalize(),
            Path.of("../../agent-tools/parkio-izmir-coverage-expansion-01/sources/izelman/parkio-named")
                    .toAbsolutePath()
                    .normalize()
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return candidates[0];
    }
}
