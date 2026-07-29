package com.parkio.parking.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class StagingVerificationGovernanceTest {

    private static final List<String> REQUIRED_STAGING_SCRIPTS = List.of(
            "scripts/staging/lib/safety-guards.sh",
            "scripts/staging/lib/evidence-common.sh",
            "scripts/staging/lib/json-helper.sh",
            "scripts/staging/run-verification-pipeline.sh",
            "scripts/staging/run-critical-journeys.sh",
            "scripts/staging/verify-restored-application-apis.sh",
            "scripts/staging/test-safety-guards.sh",
            "scripts/staging/verify-minio-roundtrip.sh",
            "scripts/staging/verify-wp05-replay.sh",
            "scripts/staging/verify-application-semantics.sh",
            "scripts/staging/validate-evidence-schema.sh");

    @Test
    void wp062EvidenceSchemaExists() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(Files.readString(
                resolve("docs/operations/evidence/operational-evidence-schema.json"), StandardCharsets.UTF_8));
        assertThat(schema.path("required").toString()).contains("evidenceSchemaVersion");
        assertThat(schema.path("properties").path("status").path("enum").toString())
                .contains("SEMANTIC_VERIFICATION_SUCCEEDED")
                .contains("APPLICATION_VERIFICATION_SUCCEEDED");
    }

    @Test
    void smokeScriptDoesNotDependOnUndeclaredJq() throws Exception {
        String smoke = Files.readString(resolve("scripts/smoke-hosted-beta.sh"), StandardCharsets.UTF_8);
        assertThat(smoke).contains("json-helper.sh");
        assertThat(smoke).contains("json_require_python");
        assertThat(smoke).contains("json_assert_jwks");
        assertThat(smoke).doesNotContain("jq -");
        assertThat(smoke).doesNotContain("jq -e");
        assertThat(smoke).doesNotContain("jq -r");
    }

    @Test
    void criticalJourneyScriptUsesBoundedStagesAndNoTokenEcho() throws Exception {
        String journeys = Files.readString(resolve("scripts/staging/run-critical-journeys.sh"), StandardCharsets.UTF_8);
        assertThat(journeys).contains("restored_login");
        assertThat(journeys).contains("refresh_rotation");
        assertThat(journeys).contains("restored_parking_read");
        assertThat(journeys).contains("restored_nearby_search");
        assertThat(journeys).contains("json_assert_jwks");
        assertThat(journeys).contains("APPLICATION_VERIFICATION_SUCCEEDED");
        assertThat(journeys).doesNotContain("echo \"$ACCESS\"");
        assertThat(journeys).doesNotContain("echo \"$PASSWORD\"");
        assertThat(journeys).doesNotContain("echo \"$REFRESH\"");
    }

    @Test
    void wp062a1ClosurePatchDocumentExists() throws Exception {
        assertThat(Files.exists(resolve("docs/operations/wp-06-02a-1-application-verification-closure-patch.md"))).isTrue();
    }

    @Test
    void stagingWorkflowSupportsOptionalJourneysWithoutPrDefault() throws Exception {
        String workflow = Files.readString(
                resolve(".github/workflows/staging-verification.yml"), StandardCharsets.UTF_8);
        assertThat(workflow).contains("PARKIO_STAGING_RUN_JOURNEYS");
        assertThat(workflow).contains("run_journeys");
        assertThat(workflow).contains("json_require_python");
        assertThat(workflow).contains("timeout-minutes:");
        assertThat(workflow).contains("if: always()");
    }

    @Test
    void gatewayMediaAndAiRoutesRemainInventoriedWithoutApprovedTimeouts() throws Exception {
        String yaml = Files.readString(
                resolve("services/gateway-service/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertThat(yaml).contains("id: media-service");
        assertThat(yaml).contains("Path=/api/v1/media/**");
        assertThat(yaml).contains("id: ai-validation-service");
        assertThat(yaml).contains("Path=/api/v1/ai-validations/**");
        assertThat(yaml).contains("PARKIO_GATEWAY_DOWNSTREAM_CONNECT_TIMEOUT:2000");
        assertThat(yaml).contains("PARKIO_GATEWAY_DOWNSTREAM_RESPONSE_TIMEOUT:30s");
    }

    @Test
    void stagingSafetyScriptsExistAndRejectProduction() throws Exception {
        for (String script : REQUIRED_STAGING_SCRIPTS) {
            assertThat(Files.exists(resolve(script))).as(script).isTrue();
        }
        String guards = Files.readString(resolve("scripts/staging/lib/safety-guards.sh"), StandardCharsets.UTF_8);
        assertThat(guards).contains("assert_not_production");
        assertThat(guards).contains("assert_safe_database_name");
        assertThat(guards).contains("assert_restore_target_not_source");
        assertThat(guards).contains("assert_safe_evidence_path");
        assertThat(guards).doesNotContain("docker system prune");
    }

    @Test
    void stagingWorkflowInvokesRestoreAndSemanticVerification() throws Exception {
        String workflow = Files.readString(
                resolve(".github/workflows/staging-verification.yml"), StandardCharsets.UTF_8);
        assertThat(workflow).contains("run-verification-pipeline.sh");
        assertThat(workflow).contains("PARKIO_STAGING_RUN_RESTORE");
        assertThat(workflow).contains("permissions:");
        assertThat(workflow).contains("timeout-minutes:");
        assertThat(workflow).contains("if: always()");
    }

    @Test
    void wp062aClosureDocumentExists() throws Exception {
        assertThat(Files.exists(resolve("docs/operations/wp-06-02a-staging-verification-closure.md"))).isTrue();
    }

    @Test
    void operationalPrometheusRulesRemainValid() throws Exception {
        Path rules = resolve("docker/prometheus/operational-readiness-recording-rules.yml");
        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        var parsed = (java.util.Map<String, Object>) yaml.load(Files.readString(rules, StandardCharsets.UTF_8));
        assertThat(parsed).containsKey("groups");
        assertThat(Files.readString(rules, StandardCharsets.UTF_8)).doesNotContain("run_id");
    }

    @Test
    void wp05KillSwitchDefaultsRemainSafe() throws Exception {
        String yaml = Files.readString(resolve("services/parking-service/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertThat(yaml).contains("PARKIO_PARKING_DECISION_AUTHORITY_ENABLED:false");
        assertThat(yaml).contains("PARKIO_CALIBRATION_ENABLED:false");
    }


    @Test
    void wp062bRestoredStackOverlayAndOrchestratorExist() throws Exception {
        assertThat(Files.exists(resolve("docker/docker-compose.restored-application-verification.yml"))).isTrue();
        assertThat(Files.exists(resolve("scripts/staging/run-wp062b-restored-stack-verification.sh"))).isTrue();
        assertThat(Files.exists(resolve("docs/operations/wp-06-02b-shared-staging-signoff-restored-database-verification.md"))).isTrue();
        assertThat(Files.exists(resolve("docs/operations/evidence/shared-staging-signoff-template.md"))).isTrue();
        String orch = Files.readString(resolve("scripts/staging/run-wp062b-restored-stack-verification.sh"), StandardCharsets.UTF_8);
        assertThat(orch).contains("SIGNOFF_REQUIRED");
        assertThat(orch).contains("assert_host_ports_free");
        assertThat(orch).contains("parkio_wp062_restore_marker");
        assertThat(orch).contains("NOT_REVIEWED");
        String guards = Files.readString(resolve("scripts/staging/lib/safety-guards.sh"), StandardCharsets.UTF_8);
        assertThat(guards).contains("assert_host_ports_free");
        String workflow = Files.readString(resolve(".github/workflows/shared-staging-verification.yml"), StandardCharsets.UTF_8);
        assertThat(workflow).contains("workflow_dispatch");
        assertThat(workflow).contains("timeout-minutes:");
        assertThat(workflow).contains("if: always()");
        assertThat(workflow.toLowerCase()).doesNotContain("environment: production");
    }

    @Test
    void wp062b1EvidenceFinalizationArtifactsExistWithoutAutoApproval() throws Exception {
        assertThat(Files.exists(resolve("docs/operations/wp-06-02b-1-evidence-finalization-signoff-preparation.md"))).isTrue();
        assertThat(Files.exists(resolve("scripts/staging/lib/wp062b1-evidence-consistency-audit.py"))).isTrue();
        assertThat(Files.exists(resolve("docs/operations/evidence/shared-staging-signoff-template.md"))).isTrue();
        String template = Files.readString(resolve("docs/operations/evidence/shared-staging-signoff-template.md"), StandardCharsets.UTF_8);
        assertThat(template).contains("NOT_REVIEWED");
        assertThat(template).doesNotContain("APPROVED_FOR_WP_06_3 by automation");
        String validate = Files.readString(resolve("scripts/staging/validate-evidence-schema.sh"), StandardCharsets.UTF_8);
        assertThat(validate).contains("summary.json");
    }

    @Test
    void wp062bEvidenceSchemaIncludesSignoffStatusesWithoutAutoApproval() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(Files.readString(
                resolve("docs/operations/evidence/operational-evidence-schema.json"), StandardCharsets.UTF_8));
        String enums = schema.path("properties").path("status").path("enum").toString();
        assertThat(enums).contains("SIGNOFF_REQUIRED");
        assertThat(enums).contains("RESTORED_STACK_STARTED");
        assertThat(enums).contains("APPROVED_FOR_WP_06_3");
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