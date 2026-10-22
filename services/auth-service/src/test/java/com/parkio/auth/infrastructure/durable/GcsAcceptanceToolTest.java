package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.StorageException;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.CaseResult;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.Caps;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.Kind;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.Status;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceProvider.Identity;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceTool.LiveConfig;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceTool.Outcome;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceTool.Recorder;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceTool.Run;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceTool.Source;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceTool.Synthetic;
import com.parkio.auth.infrastructure.durable.OfflineGcsAcceptanceProvider.Defect;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The #483 harness itself, against the offline model only: the approved sequence passes with
 * headroom under every stop threshold, each modelled provider defect fails the case that must catch
 * it, a failed production path is never concealed by a paced diagnostic, every cap stops the run, a
 * harness error or a stop still leaves a complete result, writes without an answer stay in the
 * cleanup inventory, nothing secret reaches the result, and a live run refuses unapproved settings
 * before any provider call. <strong>Not</strong> #483 evidence.
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
        assertThat(cases(outcome)).filteredOn(c -> !c.get("id").equals("S5b")).extracting(c -> c.get("status"))
                .allMatch(status -> status.equals("PASS") || status.equals("MEASURED"));
        assertThat(status(outcome, "S5b")).isEqualTo("SKIPPED");
        assertThat(evidence(outcome, "L2")).containsEntry("overwriteRequiresDelete", true);
        assertThat(evidence(outcome, "H2")).containsEntry("pagesPerEnumeration", List.of(3, 3, 3));
        assertThat(map(outcome.document().get("criteria")).values()).extracting(c -> map(c).get("status"))
                .containsOnly("PASS");

        Map<String, Object> budget = map(outcome.document().get("budget"));
        Map<String, Object> counters = map(budget.get("counters"));
        Map<String, Object> generations = map(budget.get("generations"));
        Caps caps = Caps.APPROVED;
        assertThat(number(counters, "logical")).isLessThanOrEqualTo(caps.logicalOperations() * 8 / 10);
        assertThat(number(generations, "possible")).isLessThanOrEqualTo(caps.generations() * 8 / 10);
        assertThat(number(generations, "possibleCompliance")).isLessThanOrEqualTo(caps.complianceGenerations() * 8 / 10);
        assertThat(Instant.parse((String) budget.get("latestRetainUntil")))
                .isBefore(START.plus(GcsAcceptanceCases.COMPLIANCE_RETENTION).plus(Duration.ofMinutes(15)))
                .isBefore(Instant.parse((String) map(outcome.document().get("run")).get("latestRetainUntilBound")));
        assertThat(Duration.ofSeconds(number(map(outcome.document().get("run")), "elapsedSeconds")))
                .isLessThan(caps.wallClock().dividedBy(4));
        assertThat(outcome.bundle()).isNotEmpty();
    }

    @Test
    void throttledFrontierWritesCompleteInsideOneProductionCallAndTheRaceIsExercised() {
        Outcome outcome = GcsAcceptanceTool.plan(START, CLEAN);

        Map<String, Object> facts = map(outcome.document().get("checkpointUnderThrottling"));
        assertThat(facts).containsEntry("normalPathCompleted", true)
                .containsEntry("throttlingObservedOnNormalPath", true)
                .containsEntry("externalPacingRequired", "no")
                .containsEntry("retryConvergedWithoutLoss", true)
                .containsEntry("retryScenario", "lost response of the next reservation marker")
                .containsEntry("burstWritesCompleted", true);
        assertThat(evidence(outcome, "S5r")).containsEntry("filledReservation", true);
        assertThat(number(evidence(outcome, "T1"), "throttledResponses")).isPositive();
        List<Object> rounds = list(evidence(outcome, "C1").get("rounds"));
        Map<String, Object> race = map(rounds.get(rounds.size() - 1));
        assertThat(number(race, "acknowledged")).isGreaterThanOrEqualTo(2);
        assertThat(number(race, "selfWinners")).isEqualTo(1);
    }

    @Test
    void writesWithoutAnAnswerStayInTheCleanupInventory() {
        Outcome outcome = GcsAcceptanceTool.plan(START, CLEAN);

        List<Map<String, Object>> unknown = cleanupInventory(outcome).stream().map(GcsAcceptanceToolTest::map)
                .filter(entry -> "unknown".equals(entry.get("generation"))).toList();
        assertThat(unknown).extracting(entry -> entry.get("case")).containsExactly("S4", "S5r");
        assertThat(unknown).allSatisfy(entry -> assertThat((String) entry.get("cause")).contains("SimulatedLostResponse"));
    }

    @ParameterizedTest
    @EnumSource(Defect.class)
    void everyModelledProviderDefectFailsTheCaseThatMustCatchIt(Defect defect) {
        Outcome outcome = GcsAcceptanceTool.plan(START, CLEAN, Set.of(defect), Caps.APPROVED, null, null);

        String caseId = switch (defect) {
            case LOCKED_RETENTION_SHORTENABLE, LOCKED_REFUSAL_AS_408 -> "K1";
            case RETAINED_DELETABLE -> "K4";
            case NONCURRENT_LOCKED_SHORTENABLE -> "K8";
            case LIST_DROPS_PAGE_TAIL -> "H2";
            case READER_MAY_CREATE -> "L1";
            case PROBE_ADMIN_MAY_CREATE -> "L5";
            case CONTROL_BUCKET_HAS_RETENTION_LOCK, VERSIONING_DISABLED, BUCKET_IN_ANOTHER_PROJECT,
                 SOFT_DELETE_NOT_REPORTED -> "F5";
            case RACE_GENERATIONS_DECREASE, RACE_ADMITS_ONE_WRITER -> "C1";
            case GENERATIONS_DECREASE -> "H1";
            case PROBE_ADMIN_WITHOUT_BUCKET_GET -> "F6";
            case WRITE_LIMIT_PENALTY -> "S5";
        };
        boolean preflight = caseId.startsWith("F");
        boolean inconclusive = caseId.equals("F6") || defect == Defect.LOCKED_REFUSAL_AS_408;
        boolean violation = Set.of(Defect.LOCKED_RETENTION_SHORTENABLE, Defect.RETAINED_DELETABLE,
                Defect.NONCURRENT_LOCKED_SHORTENABLE).contains(defect);
        assertThat(status(outcome, caseId)).isEqualTo(inconclusive ? "ERROR" : "FAIL");
        assertThat(outcome.verdict()).isEqualTo(preflight ? "ABORTED" : "FAIL");
        assertThat(outcome.document().get("countsFor483")).isEqualTo(false);
        assertThat(map(outcome.document().get("violations")).keySet())
                .isEqualTo(violation ? Set.of("lockedMutationAccepted") : Set.of());
        assertThat(GcsAcceptanceTool.exitCode(outcome)).isEqualTo(preflight ? 3 : violation ? 5 : 1);
        if (preflight) {
            assertThat(cleanupInventory(outcome)).isEmpty();
        }
        if (violation) {
            assertThat(status(outcome, "G5")).isEqualTo("SKIPPED");
        }
    }

    @Test
    void aPacedDiagnosticNeverConcealsAFailedProductionCall() {
        Outcome outcome = GcsAcceptanceTool.plan(START, CLEAN, Set.of(Defect.WRITE_LIMIT_PENALTY), Caps.APPROVED,
                null, null);

        assertThat(status(outcome, "S5")).isEqualTo("FAIL");
        assertThat(status(outcome, "S5b")).isEqualTo("MEASURED");
        assertThat(outcome.verdict()).isEqualTo("FAIL");
        assertThat(outcome.document().get("countsFor483")).isEqualTo(false);
        Map<String, Object> facts = map(outcome.document().get("checkpointUnderThrottling"));
        assertThat(facts).containsEntry("normalPathCompleted", false);
        assertThat(facts.get("externalPacingRequired")).isNotEqualTo("no");
        assertThat(map(map(outcome.document().get("criteria")).get("concurrency and retry semantics")))
                .containsEntry("status", "FAIL");
    }

    @Test
    void onlyPassingRequiredCasesMakeAPass() {
        assertThat(GcsAcceptanceTool.verdict(ledger(
                result("S5", Kind.ASSERTION, Status.FAIL), result("S5b", Kind.DIAGNOSTIC, Status.MEASURED))))
                .isEqualTo("FAIL");
        assertThat(GcsAcceptanceTool.verdict(ledger(
                result("S5", Kind.ASSERTION, Status.PASS), result("S5b", Kind.DIAGNOSTIC, Status.SKIPPED),
                result("L2", Kind.MEASUREMENT, Status.MEASURED)))).isEqualTo("PASS");
        assertThat(GcsAcceptanceTool.verdict(ledger(result("L2", Kind.MEASUREMENT, Status.ERROR)))).isEqualTo("FAIL");
        assertThat(GcsAcceptanceTool.verdict(ledger(result("K1", Kind.ASSERTION, Status.SKIPPED)))).isEqualTo("FAIL");
        assertThat(GcsAcceptanceTool.verdict(ledger(result("F5", Kind.PREFLIGHT, Status.MEASURED)))).isEqualTo("FAIL");
    }

    @Test
    void everyCapStopsTheRunBeforeItIsExceeded() {
        assertStopped(new Caps(1_200, 1_500, 10, 40, Duration.ofMinutes(45)), "generation cap 10");
        assertStopped(new Caps(60, 1_500, 320, 40, Duration.ofMinutes(45)), "logical operation cap 60");
        assertStopped(new Caps(1_200, 1_500, 320, 3, Duration.ofMinutes(45)), "COMPLIANCE generation cap 3");
        assertStopped(new Caps(1_200, 1_500, 320, 40, Duration.ofMinutes(2)), "wall-clock cap PT2M");
    }

    private static void assertStopped(Caps caps, String reason) {
        Outcome outcome = GcsAcceptanceTool.plan(START, CLEAN, Set.of(), caps, null, null);

        assertThat(outcome.verdict()).isEqualTo("ABORTED");
        assertThat(map(outcome.document().get("budget")).get("abortReason").toString()).contains(reason);
        assertThat(status(outcome, "G5")).isEqualTo("SKIPPED");
        Map<String, Object> generations = map(map(outcome.document().get("budget")).get("generations"));
        assertThat(number(generations, "possible")).isLessThanOrEqualTo(caps.generations());
        assertThat(number(generations, "possibleCompliance")).isLessThanOrEqualTo(caps.complianceGenerations());
        assertThat(number(map(map(outcome.document().get("budget")).get("counters")), "logical"))
                .isLessThanOrEqualTo(caps.logicalOperations());
    }

    @Test
    void aHarnessErrorStillYieldsTheResultWithEverythingSoFar() {
        OfflineGcsAcceptanceProvider offline = offline(Set.of());
        AtomicInteger transports = new AtomicInteger();
        GcsAcceptanceProvider failing = new DelegatingProvider(offline) {
            @Override
            public GcsObjectRetentionTransport transport(Identity identity, String bucket) {
                if (transports.incrementAndGet() == 40) {
                    throw new OutOfMemoryError("simulated harness failure");
                }
                return super.transport(identity, bucket);
            }
        };

        Outcome outcome = run(failing, offline.clock()).execute();

        assertThat(outcome.verdict()).isEqualTo("ERROR");
        assertThat(GcsAcceptanceTool.exitCode(outcome)).isEqualTo(4);
        assertThat((String) outcome.document().get("note")).contains("OutOfMemoryError");
        assertThat(cases(outcome)).isNotEmpty();
        assertThat(cleanupInventory(outcome)).isNotEmpty();
        assertThat(map(outcome.document().get("budget")).get("latestRetainUntil")).isNotNull();
    }

    @Test
    void aStopWhileACaseIsBlockedWritesAnInterruptedResultOnce() throws Exception {
        OfflineGcsAcceptanceProvider offline = offline(Set.of());
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GcsAcceptanceProvider stalling = new DelegatingProvider(offline) {
            @Override
            public void sleep(Duration duration) throws InterruptedException {
                if (blocked.getCount() > 0) {
                    blocked.countDown();
                    release.await(30, TimeUnit.SECONDS);
                }
                super.sleep(duration);
            }
        };
        Run run = run(stalling, offline.clock());
        Path out = temp.resolve("stopped");
        Files.createDirectories(out);
        Recorder recorder = Recorder.start(run, out, "result.json", null, Duration.ofMillis(200));
        AtomicReference<Outcome> finished = new AtomicReference<>();
        Thread main = new Thread(() -> {
            try {
                finished.set(recorder.finish(run.execute()));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        main.start();
        assertThat(blocked.await(30, TimeUnit.SECONDS)).isTrue();

        recorder.stop(run, "INTERRUPTED", "test stop");
        release.countDown();
        main.join(30_000);

        Map<String, Object> result = map(JSON.readValue(out.resolve("result.json").toFile(), Map.class));
        assertThat(result.get("verdict")).isEqualTo("INTERRUPTED");
        assertThat((String) result.get("note")).contains("test stop");
        assertThat(finished.get()).isNotNull();
        assertThat(Files.readAllLines(out.resolve(GcsAcceptanceJournal.FILE)))
                .anyMatch(line -> line.contains("\"event\":\"stopped\""))
                .anyMatch(line -> line.contains("\"event\":\"finish\""));
        assertThat(Files.readAllLines(out.resolve("SHA256SUMS"))).hasSize(3);
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
        assertThat(GcsAcceptanceLedger.sanitize("{\"accessToken\":\"tok-456\",\"expireTime\":\"x\"}"))
                .doesNotContain("tok-456");
        String jwt = "ey" + "JhbGciOiJSUzI1NiJ9.ey" + "JzdWIiOiJuMDItd3JpdGVyIn0.c2lnbmF0dXJlLXZhbHVl";
        assertThat(GcsAcceptanceLedger.sanitize("id " + jwt + " expired")).doesNotContain("JzdWIiOiJ");
        String pem = "-----BEGIN " + "PRIVATE" + " KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END " + "PRIVATE"
                + " KEY-----";
        assertThat(GcsAcceptanceLedger.sanitize("key " + pem + " in message")).doesNotContain("MIIEvQIBADAN");
    }

    @Test
    void planWritesRunRecordJournalResultBundleAndChecksumsAndNeverOverwrites() throws Exception {
        Path out = temp.resolve("plan");

        int code = GcsAcceptanceTool.execute(new String[] {"plan", out.toString()}, Map.of(), CLEAN, quiet());

        assertThat(code).isZero();
        assertThat(Files.readAllLines(out.resolve("SHA256SUMS"))).hasSize(4).allSatisfy(line -> {
            String[] parts = line.split("  ");
            assertThat(GcsAcceptanceCases.sha256(Files.readAllBytes(out.resolve(parts[1])))).isEqualTo(parts[0]);
        });
        Map<String, Object> run = map(JSON.readValue(out.resolve("run.json").toFile(), Map.class));
        assertThat(map(run.get("run"))).containsKeys("id", "namespace", "startedAt", "deadline", "latestRetainUntilBound");
        List<String> journal = Files.readAllLines(out.resolve(GcsAcceptanceJournal.FILE));
        assertThat(journal.get(0)).contains("\"event\":\"start\"");
        assertThat(journal.get(journal.size() - 1)).contains("\"event\":\"finish\"").contains("PASS");
        assertThat(journal).anyMatch(line -> line.contains("\"event\":\"write-requested\""))
                .anyMatch(line -> line.contains("\"event\":\"generation-created\""))
                .anyMatch(line -> line.contains("\"event\":\"write-unresolved\""))
                .anyMatch(line -> line.contains("\"event\":\"retention-requested\""));
        assertThatThrownBy(() -> GcsAcceptanceTool.execute(new String[] {"plan", out.toString()}, Map.of(), CLEAN,
                quiet())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never overwritten");
    }

    @Test
    void writeOutcomesAreSettledConservatively() {
        GcsAcceptanceLedger ledger = new GcsAcceptanceLedger(Clock.fixed(START, ZoneOffset.UTC), Caps.APPROVED);
        Instant until = START.plus(Duration.ofHours(2));

        long refused = ledger.writeRequested("a", EvidenceRetentionMode.COMPLIANCE, until);
        ledger.writeFailed(refused, new StorageException(403, "forbidden"));
        long throttled = ledger.writeRequested("b", EvidenceRetentionMode.COMPLIANCE, until);
        ledger.writeFailed(throttled, new StorageException(429, "rate limited"));
        long unknown = ledger.writeRequested("c", EvidenceRetentionMode.COMPLIANCE, until.plusSeconds(30));
        ledger.writeFailed(unknown, new StorageException(503, "backend error"));
        long timeout = ledger.writeRequested("d", EvidenceRetentionMode.GOVERNANCE, until);
        ledger.writeFailed(timeout, new StorageException(408, "request timeout"));
        long acknowledged = ledger.writeRequested("e", EvidenceRetentionMode.GOVERNANCE, until);
        ledger.writeAcknowledged(acknowledged, 42, 10);
        ledger.retentionRequested("e", 42, until.plusSeconds(60));

        assertThat(ledger.cleanupInventory()).extracting(entry -> entry.get("name") + "#" + entry.get("generation"))
                .containsExactly("e#42", "c#unknown", "d#unknown");
        assertThat(ledger.latestRetainUntil()).isEqualTo(until.plusSeconds(60));
        Map<String, Object> generations = map(ledger.summary().get("generations"));
        assertThat(generations).containsEntry("possible", 3L).containsEntry("possibleCompliance", 1L);
    }

    @Test
    void liveRunRefusesUnapprovedSettingsBeforeAnyProviderCall() {
        Map<String, String> approved = approvedEnvironment();
        String out = temp.resolve("run").toString();

        assertThatThrownBy(() -> run(Map.of(), CLEAN, out)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("N02_GCS_PROJECT is required").hasMessageContaining("N02_GCS_BUCKET is required")
                .hasMessageContaining("N02_GCS_PROJECT_NUMBER is required").hasMessageContaining("CLOUDSDK_CONFIG");
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
        assertThatThrownBy(() -> run(with(approved, "N02_GCS_PROJECT_NUMBER", "parkio"), CLEAN, out))
                .hasMessageContaining("N02_GCS_PROJECT_NUMBER does not match");
        assertThatThrownBy(() -> run(with(approved, "GOOGLE_SDK_JAVA_LOGGING", "true"), CLEAN, out))
                .hasMessageContaining("GOOGLE_SDK_JAVA_LOGGING");
        assertThatThrownBy(() -> run(with(approved, "GOOGLE_APPLICATION_CREDENTIALS", "/tmp/key.json"), CLEAN, out))
                .hasMessageContaining("GOOGLE_APPLICATION_CREDENTIALS must be unset");
        assertThatThrownBy(() -> run(approved, new Source("a".repeat(40), "b".repeat(40), true,
                "?? services/auth-service/src/test/java/Extra.java"), out))
                .hasMessageContaining("clean checkout").hasMessageContaining("Extra.java");
        assertThatThrownBy(() -> run(approved, new Source("", "", false), out))
                .hasMessageContaining("source SHA");
        assertThat(temp.resolve("run")).doesNotExist();
    }

    private int run(Map<String, String> env, Source source, String out) throws Exception {
        return GcsAcceptanceTool.execute(new String[] {"run", out}, env, source, quiet());
    }

    private static OfflineGcsAcceptanceProvider offline(Set<Defect> defects) {
        return new OfflineGcsAcceptanceProvider(START, GcsAcceptanceTool.PLAN_BUCKET,
                GcsAcceptanceTool.PLAN_CONTROL_BUCKET, GcsAcceptanceTool.PLAN_LOCATION, defects);
    }

    private static Run run(GcsAcceptanceProvider provider, Clock clock) {
        Map<Identity, String> accounts = new EnumMap<>(Identity.class);
        for (Identity identity : Identity.values()) {
            accounts.put(identity, "offline-" + identity.name().toLowerCase(Locale.ROOT));
        }
        LiveConfig config = new LiveConfig("offline-plan", OfflineGcsAcceptanceProvider.PROJECT_NUMBER,
                GcsAcceptanceTool.PLAN_LOCATION, GcsAcceptanceTool.PLAN_BUCKET, GcsAcceptanceTool.PLAN_CONTROL_BUCKET,
                accounts, "none (offline model)");
        return new Run(provider, new GcsAcceptanceLedger(clock, Caps.APPROVED), config, CLEAN, null, null);
    }

    private static GcsAcceptanceLedger ledger(CaseResult... results) {
        GcsAcceptanceLedger ledger = new GcsAcceptanceLedger(Clock.fixed(START, ZoneOffset.UTC), Caps.APPROVED);
        for (CaseResult result : results) {
            ledger.record(result);
        }
        return ledger;
    }

    private static CaseResult result(String id, Kind kind, Status status) {
        return new CaseResult(id, id, kind, Identity.WRITER, status, "", Map.of(), START, 0, Map.of());
    }

    /** Passes everything to the offline model; tests override one method to inject a harness failure. */
    private static class DelegatingProvider implements GcsAcceptanceProvider {

        private final GcsAcceptanceProvider delegate;

        DelegatingProvider(GcsAcceptanceProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public String evidenceClass() {
            return delegate.evidenceClass();
        }

        @Override
        public GcsObjectRetentionTransport transport(Identity identity, String bucket) {
            return delegate.transport(identity, bucket);
        }

        @Override
        public RetentionProbe probe(Identity identity) {
            return delegate.probe(identity);
        }

        @Override
        public Clock clock() {
            return delegate.clock();
        }

        @Override
        public void sleep(Duration duration) throws InterruptedException {
            delegate.sleep(duration);
        }

        @Override
        public Map<String, Object> describe() {
            return delegate.describe();
        }
    }

    private static Map<String, String> approvedEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("N02_GCS_PROJECT", "parkio-n02-accept-4c1e9b");
        env.put("N02_GCS_PROJECT_NUMBER", "123456789012");
        env.put("N02_GCS_LOCATION", "europe-west4");
        env.put("N02_GCS_BUCKET", "parkio-n02-orl-accept-20261022-4c1e9b");
        env.put("N02_GCS_CONTROL_BUCKET", "parkio-n02-noorl-ctrl-20261022-4c1e9b");
        env.put("N02_GCS_CONFIRM", "parkio-n02-orl-accept-20261022-4c1e9b");
        env.put("N02_GCS_WRITER_SA", "n02-writer@parkio-n02-accept-4c1e9b.iam.gserviceaccount.com");
        env.put("N02_GCS_WRITER_NODELETE_SA", "n02-writer-nodelete@parkio-n02-accept-4c1e9b.iam.gserviceaccount.com");
        env.put("N02_GCS_READER_SA", "n02-reader@parkio-n02-accept-4c1e9b.iam.gserviceaccount.com");
        env.put("N02_GCS_PROBE_ADMIN_SA", "n02-probe-admin@parkio-n02-accept-4c1e9b.iam.gserviceaccount.com");
        env.put("CLOUDSDK_CONFIG", "/home/operator/.config/gcloud-parkio-n02-483");
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
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static long number(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).longValue();
    }
}
