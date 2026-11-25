package com.parkio.parking.infrastructure.izum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import com.parkio.parking.infrastructure.client.IzumParkingClient;
import com.parkio.parking.infrastructure.client.Jackson2RestClientBuilder;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in live contract probe against the official IZUM Open API.
 *
 * <p>The probe fetches through the production client with the production defaults: the official URL,
 * a 2 s connect timeout, a 27 s read timeout, a 30 s whole-response bound and two retries. The feed's
 * first byte took 11.6-24.9 s on 2026-11-23 (#523), so the 5 s request timeout the probe used before
 * failed against the real feed (#615). A probe against a feed that does not answer takes about 87 s.
 *
 * <p>Disabled unless {@code PARKIO_IZUM_LIVE_PROBE=true}. Never part of default CI.
 */
@Tag("live-municipal")
class IzumLiveContractProbeTest {

    @Test
    void officialEndpointReturnsJsonArrayWithRequiredKeys() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("PARKIO_IZUM_LIVE_PROBE")),
                "Set PARKIO_IZUM_LIVE_PROBE=true to run live IZUM probe");

        MunicipalSourceProperties properties = new MunicipalSourceProperties();
        properties.getIzum().setUserAgent("ParkioParkingServiceLiveProbe/1.0");
        JsonNode feed = new IzumParkingClient(Jackson2RestClientBuilder.builder(), properties).fetch();

        assertThat(feed.isArray()).as("the feed is a JSON array").isTrue();
        assertThat(feed.size()).as("records in the feed").isPositive();
        assertThat(SchemaFingerprint.fromArray(feed).fields()).contains("ufid", "lat", "lng", "occupancy");
    }
}
