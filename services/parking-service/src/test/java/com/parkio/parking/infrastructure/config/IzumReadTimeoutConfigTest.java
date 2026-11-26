package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #523: the İZUM read timeout. On 2026-11-23 the feed sent its first byte 11.6-24.9 s after the
 * request, so the old 5 s read timeout failed every attempt. The default must cover that latency,
 * end inside {@code max-response-time}, and reach production: the production Compose overlay passes
 * {@code PARKIO_MUNICIPAL_IZUM_READ_TIMEOUT} with a default of its own, which wins over the default
 * in {@code application.yml}.
 *
 * <p>The files are read from disk on purpose: {@code src/test/resources} carries its own
 * {@code application.yml}, which shadows the production one in every test JVM.
 */
class IzumReadTimeoutConfigTest {
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(27);
    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path AZURE_COMPOSE =
            ROOT.resolve("../../docker/docker-compose.azure-hosted-beta.yml").normalize();

    @Test
    void javaDefaultMatchesTheApplicationYamlDefault() {
        assertThat(new MunicipalSourceProperties().getIzum().getReadTimeout())
                .isEqualTo(DEFAULT_READ_TIMEOUT);
    }

    @Test
    void applicationYamlDefaultsTo27SecondsAndTheEnvironmentVariableOverridesIt() {
        assertThat(bindIzum(Map.of()).getReadTimeout()).isEqualTo(DEFAULT_READ_TIMEOUT);
        assertThat(bindIzum(Map.of("PARKIO_MUNICIPAL_IZUM_READ_TIMEOUT", "15s")).getReadTimeout())
                .isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void productionComposeOverlayPassesTheSameDefault() throws Exception {
        List<String> mappings = Files.readAllLines(AZURE_COMPOSE).stream()
                .map(String::strip)
                .filter(line -> line.startsWith("PARKIO_MUNICIPAL_IZUM_READ_TIMEOUT:"))
                .toList();
        assertThat(mappings).containsExactly(
                "PARKIO_MUNICIPAL_IZUM_READ_TIMEOUT: ${PARKIO_MUNICIPAL_IZUM_READ_TIMEOUT:-27s}");
    }

    /**
     * The body is read only until {@code max-response-time} after the request starts, so a connect
     * plus a full read timeout must end before that bound; a first byte that arrives later could not
     * be used anyway.
     */
    @Test
    void connectAndReadTimeoutEndInsideTheWholeResponseBound() {
        MunicipalSourceProperties.Izum izum = bindIzum(Map.of());
        assertThat(izum.getConnectTimeout().plus(izum.getReadTimeout()))
                .isLessThan(izum.getMaxResponseTime());
    }

    /**
     * A run whose every attempt waits a full read timeout for the first byte: {@code max-retries + 1}
     * attempts of connect plus read timeout, and a backoff that {@code IzumParkingClient} caps at 1 s
     * per retry. It ends within one polling interval. Runs never overlap either way: the job uses a
     * fixed delay, counted from the end of the previous run, and the run lease skips a concurrent sync.
     */
    @Test
    void aRunOfTimedOutAttemptsEndsWithinOnePollingInterval() {
        MunicipalSourceProperties.Izum izum = bindIzum(Map.of());
        Duration attempts = izum.getConnectTimeout().plus(izum.getReadTimeout())
                .multipliedBy(izum.getMaxRetries() + 1L);
        Duration backoff = Duration.ofSeconds(izum.getMaxRetries());
        assertThat(attempts.plus(backoff)).isLessThan(Duration.ofMillis(izum.getFixedDelayMs()));
    }

    static MunicipalSourceProperties.Izum bindIzum(Map<String, Object> environment) {
        return MainApplicationYaml.bind("parkio.municipal.izum", MunicipalSourceProperties.Izum.class, environment);
    }
}
