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

class CalibrationObservabilityConfigTest {

    private static final Pattern METRIC_PATTERN = Pattern.compile("parkio_[a-z0-9_:]+");

    @Test
    void calibrationPrometheusRuleFileIsStructurallySound() throws IOException {
        Path rules = resolve("docker/prometheus/continuous-calibration-recording-rules.yml");
        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        var parsed = (java.util.Map<String, Object>) yaml.load(Files.readString(rules, StandardCharsets.UTF_8));
        assertThat(parsed).containsKey("groups");
        String content = Files.readString(rules, StandardCharsets.UTF_8);
        assertThat(content).contains("parkio:continuous_calibration:success_rate5m");
        assertThat(content).contains("parkio_parking_calibration_observation_success_total");
        assertThat(content).doesNotContain("user_id");
        assertThat(content).doesNotContain("subject_id");
    }

    @Test
    void grafanaDashboardAndProvisioningResolveCalibrationDashboard() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode dashboard = objectMapper.readTree(Files.readString(
                resolve("docker/grafana/provisioning/dashboards/parkio-continuous-calibration.json"),
                StandardCharsets.UTF_8));
        assertThat(dashboard.path("uid").asText()).isEqualTo("parkio-continuous-calibration");
        Set<Integer> panelIds = new HashSet<>();
        for (JsonNode panel : dashboard.withArray("panels")) {
            assertThat(panel.path("datasource").path("uid").asText()).isEqualTo("parkio-prometheus");
            assertThat(panelIds.add(panel.path("id").asInt())).isTrue();
        }

        String dashboardsYml = Files.readString(
                resolve("docker/grafana/provisioning/dashboards/dashboards.yml"), StandardCharsets.UTF_8);
        assertThat(dashboardsYml).contains("parkio-continuous-calibration.json");

        String compose = Files.readString(resolve("docker/docker-compose.yml"), StandardCharsets.UTF_8);
        assertThat(compose)
                .contains(
                        "./prometheus/continuous-calibration-recording-rules.yml:/etc/prometheus/continuous-calibration-recording-rules.yml:ro");
    }

    @Test
    void dashboardQueriesReferenceKnownRecordingOrRawMetrics() throws IOException {
        Set<String> allowed = Set.of(
                "parkio:continuous_calibration:success_rate5m",
                "parkio:continuous_calibration:duplicate_rate5m",
                "parkio:continuous_calibration:failure_rate5m",
                "parkio:continuous_calibration:replay_mismatch_rate5m",
                "parkio_parking_calibration_observation_success_total",
                "parkio_parking_calibration_report_duration_seconds_bucket");
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode dashboard = objectMapper.readTree(Files.readString(
                resolve("docker/grafana/provisioning/dashboards/parkio-continuous-calibration.json"),
                StandardCharsets.UTF_8));
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
