package com.parkio.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GatewayDownstreamTimeoutGovernanceTest {

    @Test
    void globalDownstreamHttpClientTimeoutsAreConfigured() throws Exception {
        String yaml = Files.readString(resolve("services/gateway-service/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertThat(yaml).contains("httpclient:");
        assertThat(yaml).contains("PARKIO_GATEWAY_DOWNSTREAM_CONNECT_TIMEOUT:2000");
        assertThat(yaml).contains("PARKIO_GATEWAY_DOWNSTREAM_RESPONSE_TIMEOUT:30s");
        assertThat(yaml).contains("PARKIO_USER_STATUS_TIMEOUT:PT2S");
        assertThat(yaml).contains("PARKIO_SESSION_EPOCH_TIMEOUT:PT2S");
    }

    private static Path resolve(String relative) {
        Path cwd = Path.of("").toAbsolutePath();
        Path direct = cwd.resolve(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        Path nested = cwd.resolve("../../" + relative.replace("/", java.io.File.separator)).normalize();
        if (Files.exists(nested)) {
            return nested;
        }
        throw new IllegalStateException("Cannot resolve " + relative + " from " + cwd);
    }
}