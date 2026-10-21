package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.Caps;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceTool.Outcome;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceTool.Source;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceTool.Synthetic;
import com.parkio.auth.infrastructure.durable.OfflineGcsAcceptanceProvider.Defect;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The #483 harness itself, against the offline model only: the approved sequence passes with
 * headroom under every stop threshold, each modelled provider defect fails the case that must catch
 * it, a cap stops the run, nothing secret reaches the result, and a live run refuses unapproved
 * settings before any provider call. <strong>Not</strong> #483 evidence.
 */
class GcsAcceptanceToolTest {

    private static final Instant START = Instant.parse("2026-10-22T09:00:00Z");
    private static final Source CLEAN = new Source("a".repeat(40), "b".repeat(40), false);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void approvedSequencePassesOfflineWithHeadroomUnderEveryCap() {
        Outcome outcome = GcsAcceptanceTool.plan(START, CLEAN);

        assertThat(outcome.verdict()).isEqualTo("PASS");
        assertThat(outcome.document().get("countsFor483")).isEqualTo(false);
        assertThat(cases(outcome)).extracting(c -> c.get("status"))
                .allMatch(status -> status.equals("PASS") || status.equals("MEASURED"));
        assertThat(evidence(outcome, "L2")).containsEntry("overwriteRequiresDelete", true);
        assertThat(evidence(outcome, "H2")).containsEntry("pagesPerEnumeration", List.of(3, 3, 3));

        Map<String, Object> budget = map(outcome.document().get("budget"));
        Map<String, Object> counters = map(budget.get("counters"));
        Map<String, Object> generations = map(budget.get("generations"));
        Caps caps = Caps.APPROVED;
        assertThat(number(counters, "logical")).isLessThanOrEqualTo(caps.logicalOperations() * 8 / 10);
        assertThat(number(generations, "governance") + number(generations, "compliance"))
                .isLessThanOrEqualTo(caps.generations() * 8 / 10);
        assertThat(number(generations, "compliance")).isLessThanOrEqualTo(caps.complianceGenerations() * 8 / 10);
        assertThat(Instant.parse((String) budget.get("latestRetainUntil")))
                .isBefore(START.plus(GcsAcceptanceCases.COMPLIANCE_RETENTION).plus(Duration.ofMinutes(15)));
        assertThat(Duration.ofSeconds(number(map(outcome.document().get("run")), "elapsedSeconds")))
                .isLessThan(caps.wallClock().dividedBy(4));
        assertThat(outcome.bundle()).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(Defect.class)
    void everyModelledProviderDefectFailsTheCaseThatMustCatchIt(Defect defect) {
        Outcome outcome = GcsAcceptanceTool.plan(START, CLEAN, Set.of(defect), Caps.APPROVED, null, null);

        String caseId = switch (defect) {
            case LOCKED_RETENTION_SHORTENABLE -> "K1";
            case RETAINED_DELETABLE -> "K4";
            case LIST_DROPS_PAGE_TAIL -> "H2";
            case READER_MAY_CREATE -> "L1";
            case CONTROL_BUCKET_HAS_RETENTION_LOCK, VERSIONING_DISABLED -> "F5";
            case GENERATIONS_DECREASE -> "H1";
            case PROBE_ADMIN_WITHOUT_BUCKET_GET -> "F6";
        };
        boolean preflight = caseId.startsWith("F");
        assertThat(status(outcome, caseId)).isEqualTo(caseId.equals("F6") ? "ERROR" : "FAIL");
        assertThat(outcome.verdict()).isEqualTo(preflight ? "ABORTED" : "FAIL");
        if (preflight) {
            assertThat(cleanupInventory(outcome)).isEmpty();
        }
    }

    @Test
    void reachedCapStopsTheRunBeforeTheNextWrite() {
        Caps tight = new Caps(1_200, 1_500, 10, 40, Duration.ofMinutes(45));

        Outcome outcome = GcsAcceptanceTool.plan(START, CLEAN, Set.of(), tight, null, null);

        assertThat(outcome.verdict()).isEqualTo("ABORTED");
        assertThat(cleanupInventory(outcome)).hasSize(10);
        assertThat(status(outcome, "G4")).isEqualTo("SKIPPED");
        assertThat(map(outcome.document().get("budget")).get("abortReason").toString()).contains("generation cap 10");
    }

    @Test
    void resultAndBundleCarryNoSecretMaterial() throws Exception {
        byte[] secret = "n02-harness-test-hmac-key-not-a-secret!!".getBytes(StandardCharsets.UTF_8);
        TrustedKey key = TrustedKey.active("n02-accept-test", "n02-gcs-acceptance", secret, START.minusSeconds(300));
        Synthetic synthetic = new Synthetic(new EvidenceTrust("postgresql:7000000000000000123:parkio_n02_acceptance",
                List.of(key)), key.keyId());

        Outcome outcome = GcsAcceptanceTool.plan(START, CLEAN, Set.of(), Caps.APPROVED, synthetic, null);

        String document = JSON.writeValueAsString(outcome.document());
        String bundle = new String(outcome.bundle(), StandardCharsets.UTF_8);
        for (String encoded : List.of(new String(secret, StandardCharsets.UTF_8), HexFormat.of().formatHex(secret),
                Base64.getEncoder().encodeToString(secret))) {
            assertThat(document).doesNotContain(encoded);
            assertThat(bundle).doesNotContain(encoded);
        }
        assertThat(GcsAcceptanceLedger.sanitize("Authorization: Bearer ya29.a0Abc-def_123 rejected"))
                .doesNotContain("ya29.")
                .doesNotContain("a0Abc");
        assertThat(GcsAcceptanceLedger.sanitize("token ya29.a0Xyz-789 was rejected")).doesNotContain("a0Xyz");
        assertThat(GcsAcceptanceLedger.sanitize("{\"access_token\": \"tok-123\", \"code\": 403}"))
                .doesNotContain("tok-123")
                .contains("403");
    }

    @Test
    void planWritesResultBundleAndChecksumsAndNeverOverwrites() throws Exception {
        Path out = temp.resolve("plan");

        int code = GcsAcceptanceTool.execute(new String[] {"plan", out.toString()}, Map.of(), CLEAN, quiet());

        assertThat(code).isZero();
        assertThat(Files.readAllLines(out.resolve("SHA256SUMS"))).hasSize(2).allSatisfy(line -> {
            String[] parts = line.split("  ");
            assertThat(GcsAcceptanceCases.sha256(Files.readAllBytes(out.resolve(parts[1])))).isEqualTo(parts[0]);
        });
        assertThatThrownBy(() -> GcsAcceptanceTool.execute(new String[] {"plan", out.toString()}, Map.of(), CLEAN,
                quiet())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never overwritten");
    }

    @Test
    void liveRunRefusesUnapprovedSettingsBeforeAnyProviderCall() {
        Map<String, String> approved = approvedEnvironment();
        String out = temp.resolve("run").toString();

        assertThatThrownBy(() -> run(Map.of(), CLEAN, out)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("N02_GCS_PROJECT is required").hasMessageContaining("N02_GCS_BUCKET is required");
        assertThatThrownBy(() -> run(with(approved, "N02_GCS_CONFIRM", "parkio-n02-orl-accept-20261022-000001"), CLEAN, out))
                .hasMessageContaining("N02_GCS_CONFIRM");
        assertThatThrownBy(() -> run(with(approved, "N02_GCS_BUCKET", "parkio-erasure-evidence-prod"), CLEAN, out))
                .hasMessageContaining("N02_GCS_BUCKET does not match");
        assertThatThrownBy(() -> run(with(approved, "N02_GCS_CONTROL_BUCKET", "parkio-n02-noorl-ctrl-20261023-4c1e9b"),
                CLEAN, out)).hasMessageContaining("date and suffix");
        assertThatThrownBy(() -> run(with(approved, "N02_GCS_READER_SA", "n02-reader@other-project.iam.gserviceaccount.com"),
                CLEAN, out)).hasMessageContaining("N02_GCS_READER_SA does not match");
        assertThatThrownBy(() -> run(with(approved, "N02_GCS_READER_SA", approved.get("N02_GCS_WRITER_SA")), CLEAN, out))
                .hasMessageContaining("its own service account");
        assertThatThrownBy(() -> run(approved, new Source("a".repeat(40), "b".repeat(40), true), out))
                .hasMessageContaining("clean checkout");
        assertThatThrownBy(() -> run(approved, new Source("", "", false), out))
                .hasMessageContaining("source SHA");
        assertThat(temp.resolve("run")).doesNotExist();
    }

    private int run(Map<String, String> env, Source source, String out) throws Exception {
        return GcsAcceptanceTool.execute(new String[] {"run", out}, env, source, quiet());
    }

    private static Map<String, String> approvedEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("N02_GCS_PROJECT", "parkio-n02-accept-4c1e9b");
        env.put("N02_GCS_LOCATION", "europe-west4");
        env.put("N02_GCS_BUCKET", "parkio-n02-orl-accept-20261022-4c1e9b");
        env.put("N02_GCS_CONTROL_BUCKET", "parkio-n02-noorl-ctrl-20261022-4c1e9b");
        env.put("N02_GCS_CONFIRM", "parkio-n02-orl-accept-20261022-4c1e9b");
        env.put("N02_GCS_WRITER_SA", "n02-writer@parkio-n02-accept-4c1e9b.iam.gserviceaccount.com");
        env.put("N02_GCS_WRITER_NODELETE_SA", "n02-writer-nodelete@parkio-n02-accept-4c1e9b.iam.gserviceaccount.com");
        env.put("N02_GCS_READER_SA", "n02-reader@parkio-n02-accept-4c1e9b.iam.gserviceaccount.com");
        env.put("N02_GCS_PROBE_ADMIN_SA", "n02-probe-admin@parkio-n02-accept-4c1e9b.iam.gserviceaccount.com");
        return env;
    }

    private static Map<String, String> with(Map<String, String> env, String key, String value) {
        Map<String, String> copy = new HashMap<>(env);
        copy.put(key, value);
        return copy;
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(Outcome outcome) {
        return (List<Map<String, Object>>) outcome.document().get("cases");
    }

    private static String status(Outcome outcome, String id) {
        return cases(outcome).stream().filter(c -> c.get("id").equals(id)).findFirst()
                .map(c -> (String) c.get("status")).orElse("MISSING");
    }

    private static Map<String, Object> evidence(Outcome outcome, String id) {
        return cases(outcome).stream().filter(c -> c.get("id").equals(id)).findFirst()
                .map(c -> map(c.get("evidence"))).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> cleanupInventory(Outcome outcome) {
        return (List<Object>) outcome.document().get("cleanupInventory");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static long number(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).longValue();
    }
}
