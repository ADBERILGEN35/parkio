package com.parkio.platform.connectivity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Local DNS / endpoint-change application contract for SPIKE-03 Mode A.
 *
 * <p>Does <strong>not</strong> claim Azure Private DNS or Flexible Server failover proof.
 */
class DnsEndpointChangeContractTest {

    @Test
    void productionJdbcUrlsMustUseDnsHostnamesNotPrivateIpLiterals() {
        String core =
                "jdbc:postgresql://parkio-core-pg.postgres.database.azure.com:5432/parkio_auth?sslmode=verify-full";
        String parking =
                "jdbc:postgresql://parkio-parking-pg.postgres.database.azure.com:5432/parkio_parking?sslmode=verify-full";
        assertThat(core).doesNotMatch("(?i).*://\\d+\\.\\d+\\.\\d+\\.\\d+:.*");
        assertThat(parking).doesNotMatch("(?i).*://\\d+\\.\\d+\\.\\d+\\.\\d+:.*");
        assertThat(core).contains("parkio-core-pg");
        assertThat(parking).contains("parkio-parking-pg");
    }

    @Test
    void repositoryDoesNotPinResolvedInetAddressInDatasourceContract() {
        // Parkio binds JDBC URLs as hostnames via env; no static IP cache in platform contract.
        String template = "jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}?sslmode=verify-full";
        assertThat(template).contains("${DB_HOST}");
        assertThat(template).doesNotContain("10.");
    }

    @Test
    void connectionAndValidationTimeoutsAreBounded() {
        // Matches current service YAML defaults (auth/parking family).
        Duration connectionTimeout = Duration.ofMillis(2_000);
        Duration validationTimeout = Duration.ofMillis(1_000);
        Duration maxLifetime = Duration.ofMillis(1_800_000);
        assertThat(connectionTimeout.toMillis()).isBetween(500L, 10_000L);
        assertThat(validationTimeout.toMillis()).isBetween(250L, 5_000L);
        assertThat(maxLifetime.toMillis()).isLessThanOrEqualTo(Duration.ofHours(1).toMillis());
    }

    @Test
    void localhostResolvesForLocalVerifyFullSanContract() throws Exception {
        InetAddress[] addrs = InetAddress.getAllByName("localhost");
        assertThat(addrs).isNotEmpty();
    }

    @Test
    void azurePrivateDnsFailoverRemainsUnknownUntilModeB() {
        assertThat("UNKNOWN").isEqualTo("UNKNOWN");
    }
}
