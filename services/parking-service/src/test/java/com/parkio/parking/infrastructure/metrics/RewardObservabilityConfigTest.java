package com.parkio.parking.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class RewardObservabilityConfigTest {

    private static final Pattern METRIC_PATTERN = Pattern.compile("parkio_[a-z0-9_:]+");

    @Test
    void rewardPrometheusRuleFileIsStructurallySound() throws IOException {
        Path rules = resolve("docker/prometheus/reward-shadow-recording-rules.yml");
        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        var parsed = (java.util.Map<String, Object>) yaml.load(Files.readString(rules, StandardCharsets.UTF_8));
        assertThat(parsed).containsKey("groups");
        String content = Files.readString(rules, StandardCharsets.UTF_8);
        assertThat(content).contains("parkio:reward_shadow:success_rate5m");
        assertThat(content).contains("parkio_parking_reward_evaluation_success_total");
        assertThat(content).doesNotContain("subject_id");
        assertThat(content).doesNotContain("user_id");
    }

    @Test
    void grafanaDashboardAndProvisioningResolveRewardDashboard() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode dashboard = objectMapper.readTree(
                Files.readString(resolve("docker/grafana/provisioning/dashboards/parkio-reward-shadow.json"), StandardCharsets.UTF_8));
        assertThat(dashboard.path("uid").asText()).isEqualTo("parkio-reward-shadow");
        Set<Integer> panelIds = new HashSet<>();
        for (JsonNode panel : dashboard.withArray("panels")) {
            assertThat(panel.path("datasource").path("uid").asText()).isEqualTo("parkio-prometheus");
            assertThat(panelIds.add(panel.path("id").asInt())).isTrue();
        }

        String dashboardsYml = Files.readString(
                resolve("docker/grafana/provisioning/dashboards/dashboards.yml"), StandardCharsets.UTF_8);
        assertThat(dashboardsYml).contains("path: /etc/grafana/provisioning/dashboards");
        assertThat(dashboardsYml).contains("parkio-reward-shadow.json");

        String compose = Files.readString(resolve("docker/docker-compose.yml"), StandardCharsets.UTF_8);
        assertThat(compose).contains("./grafana/provisioning:/etc/grafana/provisioning:ro");
        assertThat(compose).contains("./prometheus/reward-shadow-recording-rules.yml:/etc/prometheus/reward-shadow-recording-rules.yml:ro");
    }

    @Test
    void dashboardQueriesReferenceKnownRecordingOrRawMetrics() throws IOException {
        Set<String> allowed = Set.of(
                "parkio:reward_shadow:success_rate5m",
                "parkio:reward_shadow:duplicate_rate5m",
                "parkio:reward_shadow:eligible_ratio5m",
                "parkio_parking_reward_replay_mismatch_total",
                "parkio_parking_reward_disposition_total",
                "parkio_parking_reward_evaluation_success_total",
                "parkio_parking_reward_contribution_produced_total",
                "parkio_parking_reward_evaluation_duration_seconds_bucket");
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode dashboard = objectMapper.readTree(
                Files.readString(resolve("docker/grafana/provisioning/dashboards/parkio-reward-shadow.json"), StandardCharsets.UTF_8));
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

    @Test
    void rewardRelatedFilesContainNoNulBytesOrUtf8Bom() throws IOException {
        for (String relative : List.of(
                "services/parking-service/src/main/java/com/parkio/parking/reward/RewardEngine.java",
                "services/parking-service/src/main/java/com/parkio/parking/application/RewardShadowApplicationService.java",
                "services/parking-service/src/main/resources/db/migration/V24__pending_reward_shadow_ledger.sql",
                "services/parking-service/src/test/java/com/parkio/parking/infrastructure/persistence/reward/RewardShadowMigrationPostgresIT.java",
                "services/parking-service/src/test/java/com/parkio/parking/infrastructure/persistence/reward/RewardShadowPersistencePostgresIT.java",
                "docker/prometheus/reward-shadow-recording-rules.yml",
                "docker/grafana/provisioning/dashboards/parkio-reward-shadow.json")) {
            byte[] bytes = Files.readAllBytes(resolve(relative));
            assertThat(indexOf(bytes, (byte) 0)).as(relative + " contains NUL bytes").isEqualTo(-1);
            if (bytes.length >= 3) {
                assertThat(bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF)
                        .as(relative + " has UTF-8 BOM")
                        .isFalse();
            }
        }
    }

    private static int indexOf(byte[] haystack, byte needle) {
        for (int i = 0; i < haystack.length; i++) {
            if (haystack[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    private static Path resolve(String relative) {
        Path probe = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && probe != null; i++) {
            Path candidate = probe.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            probe = probe.getParent();
        }
        throw new IllegalStateException("Cannot resolve path: " + relative);
    }
}
