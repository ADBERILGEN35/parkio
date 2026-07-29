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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class FraudObservabilityConfigTest {

    private static final Pattern METRIC_PATTERN = Pattern.compile("parkio_[a-z0-9_:]+");

    @Test
    void fraudPrometheusRuleFileIsStructurallySound() throws IOException {
        Path rules = resolve("docker/prometheus/fraud-shadow-recording-rules.yml");
        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        var parsed = (java.util.Map<String, Object>) yaml.load(Files.readString(rules, StandardCharsets.UTF_8));
        assertThat(parsed).containsKey("groups");
        String content = Files.readString(rules, StandardCharsets.UTF_8);
        assertThat(content).contains("parkio:fraud_shadow:success_rate5m");
        assertThat(content).contains("parkio_parking_fraud_evaluation_success_total");
        assertThat(content).doesNotContain("user_id");
        assertThat(content).doesNotContain("subject_id");
    }

    @Test
    void grafanaDashboardAndProvisioningResolveFraudDashboard() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode dashboard = objectMapper.readTree(
                Files.readString(resolve("docker/grafana/provisioning/dashboards/parkio-fraud-shadow.json"), StandardCharsets.UTF_8));
        assertThat(dashboard.path("uid").asText()).isEqualTo("parkio-fraud-shadow");
        Set<Integer> panelIds = new HashSet<>();
        for (JsonNode panel : dashboard.withArray("panels")) {
            assertThat(panel.path("datasource").path("uid").asText()).isEqualTo("parkio-prometheus");
            assertThat(panelIds.add(panel.path("id").asInt())).isTrue();
        }

        String dashboardsYml = Files.readString(
                resolve("docker/grafana/provisioning/dashboards/dashboards.yml"), StandardCharsets.UTF_8);
        assertThat(dashboardsYml).contains("parkio-fraud-shadow.json");

        String compose = Files.readString(resolve("docker/docker-compose.yml"), StandardCharsets.UTF_8);
        assertThat(compose).contains("./prometheus/fraud-shadow-recording-rules.yml:/etc/prometheus/fraud-shadow-recording-rules.yml:ro");
    }

    @Test
    void dashboardQueriesReferenceKnownRecordingOrRawMetrics() throws IOException {
        Set<String> allowed = Set.of(
                "parkio:fraud_shadow:success_rate5m",
                "parkio:fraud_shadow:duplicate_rate5m",
                "parkio:fraud_shadow:failure_rate5m",
                "parkio:fraud_shadow:replay_mismatch_rate5m",
                "parkio_parking_fraud_evaluation_success_total",
                "parkio_parking_fraud_evaluation_duration_seconds_bucket");
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode dashboard = objectMapper.readTree(
                Files.readString(resolve("docker/grafana/provisioning/dashboards/parkio-fraud-shadow.json"), StandardCharsets.UTF_8));
        for (JsonNode panel : dashboard.withArray("panels")) {
            for (JsonNode target : panel.withArray("targets")) {
                String expr = target.path("expr").asText();
                Matcher matcher = METRIC_PATTERN.matcher(expr);
                while (matcher.find()) {
                    assertThat(allowed).contains(matcher.group());
                }
            }
        }
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
