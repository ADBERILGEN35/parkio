package com.parkio.parking.infrastructure.izum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in live contract probe against the official IZUM Open API.
 *
 * <p>Disabled unless {@code PARKIO_IZUM_LIVE_PROBE=true}. Never part of default CI.
 */
@Tag("live-municipal")
class IzumLiveContractProbeTest {
    private static final String URL = "https://openapi.izmir.bel.tr/api/ibb/izum/otoparklar";

    @Test
    void officialEndpointReturnsJsonArrayWithRequiredKeys() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("PARKIO_IZUM_LIVE_PROBE")),
                "Set PARKIO_IZUM_LIVE_PROBE=true to run live IZUM probe");

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("User-Agent", "ParkioParkingServiceLiveProbe/1.0")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/json");
        assertThat(response.body()).contains("\"ufid\"").contains("\"occupancy\"");
    }
}
