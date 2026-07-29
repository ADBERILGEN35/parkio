package com.parkio.parking.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OperationalReadinessGovernanceTest {

    @Test
    void operationalReadinessPrometheusRulesAreValid() throws IOException {
        Path rules = resolve("docker/prometheus/operational-readiness-recording-rules.yml");
        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        var parsed = (java.util.Map<String, Object>) yaml.load(Files.readString(rules, StandardCharsets.UTF_8));
        assertThat(parsed).containsKey("groups");
        String content = Files.readString(rules, StandardCharsets.UTF_8);
        assertThat(content).contains("parkio:gateway:availability_ratio5m");
        assertThat(content).contains("http_server_requests_seconds_count");
        assertThat(content).doesNotContain("user_id");
    }

    @Test
    void operationalReadinessDashboardStructure() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode dashboard = objectMapper.readTree(Files.readString(
                resolve("docker/grafana/provisioning/dashboards/parkio-operational-readiness.json"),
                StandardCharsets.UTF_8));
        assertThat(dashboard.path("uid").asText()).isEqualTo("parkio-operational-readiness");
        Set<Integer> panelIds = new HashSet<>();
        for (JsonNode panel : dashboard.withArray("panels")) {
            assertThat(panel.path("datasource").path("uid").asText()).isEqualTo("parkio-prometheus");
            assertThat(panelIds.add(panel.path("id").asInt())).isTrue();
        }
        String compose = Files.readString(resolve("docker/docker-compose.yml"), StandardCharsets.UTF_8);
        assertThat(compose).contains("./prometheus/operational-readiness-recording-rules.yml");
    }

    @Test
    void wp06HubDocumentExists() throws IOException {
        assertThat(Files.exists(resolve("docs/operations/wp-06-01-operational-readiness-production-governance.md"))).isTrue();
        assertThat(Files.exists(resolve("docs/operations/runbooks/auth-outage.md"))).isTrue();
    }

    @Test
    void wp05KillSwitchDefaultsRemainSafe() throws IOException {
        String yaml = Files.readString(resolve("services/parking-service/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertThat(yaml).contains("PARKIO_PARKING_DECISION_AUTHORITY_ENABLED:false");
        assertThat(yaml).contains("PARKIO_CALIBRATION_ENABLED:false");
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