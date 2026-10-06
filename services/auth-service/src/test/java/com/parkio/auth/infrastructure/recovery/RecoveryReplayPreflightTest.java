package com.parkio.auth.infrastructure.recovery;

import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.ATTEMPT;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.DATASET;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.EVIDENCE_IDENTITY;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.TARGET;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.USERS;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.args;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkio.auth.infrastructure.recovery.RecoveryFixtures.Target;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * The checks that run before any Spring context exists (owner decision P6 and its conditions, D1;
 * PR #295 review B3, B5, N1, N3, N5). Each refusal names its exit code and reason, and stops before
 * the next check: an evidence refusal never reads the target. Synthetic ids and keys only.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RecoveryReplayPreflightTest {

    private static final Pattern FORBIDDEN_CLAIMS = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T|no later|absen|cutoff|complete coverage|all erasures|until now",
            Pattern.CASE_INSENSITIVE);
    private static final String PRODUCTION_CLUSTER_OTHER_DATABASE = "postgresql:7000000000000000001:parkio_auth_restore";

    @TempDir Path dir;

    private MockEnvironment environment;
    private Path evidence;
    private Path trust;
    private Path verdictFile;
    private Target target;

    @BeforeEach
    void inputs() {
        environment = RecoveryFixtures.environment();
        evidence = RecoveryFixtures.trustedSet(dir, ATTEMPT, DATASET, TARGET);
        trust = RecoveryFixtures.trustDocument(dir, EVIDENCE_IDENTITY);
        verdictFile = dir.resolve("verdict.json");
        target = Target.of(TARGET);
    }

    @Test
    void anAcceptedRunCarriesTheVerifiedSetTheConnectedIdentityAndThePollInterval() {
        RecoveryReplayVerdict verdict = verdict();

        RecoveryReplayPreflight.Plan plan = check(validArgs(), verdict);

        assertThat(plan.connectedIdentity()).isEqualTo(TARGET);
        assertThat(plan.set().entries()).hasSize(USERS);
        assertThat(plan.pollInterval()).isEqualTo(Duration.ofMillis(100));
        assertThat(target.identityReads).isEqualTo(1);
        assertThat(target.anchorReads).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) verdict.get("coverage");
        assertThat(coverage).containsEntry("ignoredFrontierVersions", 0).containsEntry("verifiedThroughSequence", 4L);
        assertThat(coverage.get("statement").toString())
                .matches("erasure coverage verified through sequence 4 \\(frontier version sha256:[0-9a-f]{64}\\)");
        assertThat(FORBIDDEN_CLAIMS.matcher(coverage.toString()).find()).isFalse();
    }

    @Test
    void withoutTheProfileNothingIsChecked() {
        environment.setActiveProfiles("prod");

        refused(RecoveryReplayExit.REFUSED, "the recovery-replay profile is not active");
        assertTargetUntouched();
    }

    @Test
    void anyWebApplicationTypeButNoneIsRefused() {
        for (String type : List.of("servlet", "REACTIVE", " servlet ")) {
            environment.setProperty(RecoveryReplayPreflight.WEB_APPLICATION_TYPE, type);
            refused(RecoveryReplayExit.REFUSED,
                    "the command runs without a web server; spring.main.web-application-type=" + type + " is refused");
        }
        assertTargetUntouched();
        environment.setProperty(RecoveryReplayPreflight.WEB_APPLICATION_TYPE, "none");
        check(validArgs(), verdict());
    }

    @Test
    void theProfileWithoutTheFlagIsRefused() {
        environment.setProperty(RecoveryReplayPreflight.FLAG, "false");

        refused(RecoveryReplayExit.REFUSED,
                "restore replay is disabled (parkio.privacy.account-erasure.restore-replay.enabled=false)");
        assertTargetUntouched();
    }

    @Test
    void everyDurableStoreWriterIsRefusedByName() {
        // Literal names: a writer dropped from the production list must fail here (review N3, M6).
        for (String writer : List.of(
                "parkio.privacy.account-erasure.durable-recording-enabled",
                "parkio.privacy.account-erasure.durable-recording-retry-worker-enabled",
                "parkio.privacy.account-erasure.durable-store.object-lock.enabled",
                "parkio.privacy.account-erasure.durable-store.checkpoint.enabled")) {
            MockEnvironment withWriter = RecoveryFixtures.environment();
            withWriter.setProperty(writer, "true");
            environment = withWriter;

            refused(RecoveryReplayExit.REFUSED,
                    "a durable-store writer is enabled; a restored copy must not write evidence: [" + writer + "]");
        }
        assertTargetUntouched();
    }

    @Test
    void thePollIntervalMustBeAPositiveDuration() {
        for (String interval : List.of("PT0S", "-PT1S")) {
            environment.setProperty(RecoveryReplayPreflight.POLL_INTERVAL, interval);
            refused(RecoveryReplayExit.REFUSED,
                    "parkio.privacy.account-erasure.recovery-replay.poll-interval must be positive");
        }
        environment.setProperty(RecoveryReplayPreflight.POLL_INTERVAL, "soon");
        refused(RecoveryReplayExit.REFUSED,
                "parkio.privacy.account-erasure.recovery-replay.poll-interval is not a duration");
        environment.setProperty(RecoveryReplayPreflight.POLL_INTERVAL, "2s");
        assertThat(check(validArgs(), verdict()).pollInterval()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void untrustedEvidenceIsRefusedBeforeTheTargetIsRead() {
        for (String bundle : List.of("gap", "missing-frontier", "frontier-all-tampered", "tail-conflict",
                "frontier-newest-tampered", "listed-above-frontier", "checkpoint-key-swap",
                "record-under-another-key", "duplicate-sequence", "checkpoint-duplicate-user")) {
            evidence = RecoveryFixtures.trustedSetWithBundle(dir, bundle);

            assertThatThrownBy(() -> check(validArgs(), verdict()))
                    .as(bundle).isInstanceOfSatisfying(RecoveryReplayRefusal.class, refusal -> {
                        assertThat(refusal.exit()).isEqualTo(RecoveryReplayExit.INVALID_EVIDENCE);
                        assertThat(refusal.getMessage()).startsWith("evidence does not verify: ");
                    });
        }
        assertTargetUntouched();
    }

    @Test
    void aTrustDocumentForAnotherDatabaseRefusesTheEvidence() {
        trust = RecoveryFixtures.trustDocument(dir, "postgresql:7000000000000000002:parkio_auth");

        refused(RecoveryReplayExit.INVALID_EVIDENCE, "evidence does not verify: database identity mismatch");
        assertTargetUntouched();
    }

    @Test
    void anAttemptOrDatasetOtherThanTheTrustedSetIsRefused() {
        assertRefused(args(evidence, trust, UUID.randomUUID().toString(), DATASET, TARGET, verdictFile),
                RecoveryReplayExit.ATTEMPT_MISMATCH, "the attempt or dataset does not match the trusted-set file");
        assertRefused(args(evidence, trust, ATTEMPT, "another-dataset", TARGET, verdictFile),
                RecoveryReplayExit.ATTEMPT_MISMATCH, "the attempt or dataset does not match the trusted-set file");
        assertTargetUntouched();
    }

    @Test
    void anUnreadableConnectedIdentityIsRefused() {
        for (Target unreadable : List.of(
                new Target(() -> {
                    throw new IllegalStateException("permission denied for pg_control_system");
                }, List::of),
                Target.of(null), Target.of(" "))) {
            target = unreadable;
            refused(RecoveryReplayExit.TARGET_REFUSED, "the connected database identity is unreadable");
            assertThat(target.anchorReads).isZero();
        }
    }

    @Test
    void theProductionClusterIsRefusedWhateverTheDatabaseName() {
        String reason = "the target is refused: target identity is on the cluster of the production identity pinned"
                + " in the trust document";
        // Connected to the production database itself, named as the ticket's target.
        target = Target.of(EVIDENCE_IDENTITY);
        evidence = RecoveryFixtures.trustedSet(dir, ATTEMPT, DATASET, EVIDENCE_IDENTITY);
        assertRefused(args(evidence, trust, ATTEMPT, DATASET, EVIDENCE_IDENTITY, verdictFile),
                RecoveryReplayExit.TARGET_REFUSED, reason);
        // Another database on the production cluster (a scratch restore, a renamed PITR clone).
        target = Target.of(PRODUCTION_CLUSTER_OTHER_DATABASE);
        evidence = RecoveryFixtures.trustedSet(dir, ATTEMPT, DATASET, PRODUCTION_CLUSTER_OTHER_DATABASE);
        assertRefused(args(evidence, trust, ATTEMPT, DATASET, PRODUCTION_CLUSTER_OTHER_DATABASE, verdictFile),
                RecoveryReplayExit.TARGET_REFUSED, reason);
        // The ticket names the production cluster while the connection is elsewhere.
        target = Target.of(TARGET);
        assertRefused(args(evidence, trust, ATTEMPT, DATASET, PRODUCTION_CLUSTER_OTHER_DATABASE, verdictFile),
                RecoveryReplayExit.TARGET_REFUSED, reason);
        assertThat(target.anchorReads).isZero();
    }

    @Test
    void anAmbiguousIdentityIsRefused() {
        target = Target.of("postgresql:07000000000000000099:parkio_auth");
        refused(RecoveryReplayExit.TARGET_REFUSED, "the target is refused: ambiguous database identity");
        target = Target.of(TARGET);
        assertRefused(args(evidence, trust, ATTEMPT, DATASET, "postgresql:7000000000000000099", verdictFile),
                RecoveryReplayExit.TARGET_REFUSED, "the target is refused: ambiguous database identity");
    }

    @Test
    void aTargetOtherThanTheTicketsIsRefused() {
        String elsewhere = "postgresql:7000000000000000123:parkio_auth";
        String reason = "the connected database is not the isolated target named by the ticket";
        target = Target.of(elsewhere);
        refused(RecoveryReplayExit.TARGET_REFUSED, reason);
        target = Target.of(TARGET);
        evidence = RecoveryFixtures.trustedSet(dir, ATTEMPT, DATASET, elsewhere);
        refused(RecoveryReplayExit.TARGET_REFUSED, reason);
        assertThat(target.anchorReads).isZero();
    }

    @Test
    void theSharedIdentityCasesGiveTheSameDecisionsAsThePythonRestore() {
        JsonNode cases = RecoveryFixtures.json("database-identities.json");
        String production = cases.path("productionIdentity").asText();
        for (JsonNode item : cases.path("cases")) {
            String identity = item.path("target").asText();
            if (item.path("refusal").isNull()) {
                RecoveryReplayPreflight.checkTarget(identity, identity, identity, production);
                continue;
            }
            assertThatThrownBy(() -> RecoveryReplayPreflight.checkTarget(identity, identity, identity, production))
                    .as(item.path("name").asText())
                    .isInstanceOfSatisfying(RecoveryReplayRefusal.class, refusal -> {
                        assertThat(refusal.exit()).isEqualTo(RecoveryReplayExit.TARGET_REFUSED);
                        assertThat(refusal.getMessage()).isEqualTo("the target is refused: " + item.path("refusal").asText());
                    });
        }
    }

    @Test
    void aDurablyRecordedErasureMissingFromTheTrustedSetIsRefused() {
        target = new Target(() -> TARGET, () -> List.of(UUID.randomUUID()));

        refused(RecoveryReplayExit.INVALID_EVIDENCE, "the trusted set lacks 1 erasure(s) the restored auth database"
                + " marks DURABLY_RECORDED; the evidence is older than the backup");
    }

    @Test
    void anUnreadableAnchorIsRefused() {
        // A backup taken before V24 has no durable_recording_status column: it cannot be anchored.
        target = new Target(() -> TARGET, () -> {
            throw new IllegalStateException("target read failed: 42703");
        });

        refused(RecoveryReplayExit.INVALID_EVIDENCE, "the restored auth database's durable-recording state cannot be"
                + " read, so the evidence cannot be anchored to the backup");
    }

    @Test
    void unreadableInputFilesAreRefused() {
        assertRefused(args(dir.resolve("missing.json"), trust, ATTEMPT, DATASET, TARGET, verdictFile),
                RecoveryReplayExit.REFUSED, "trusted-set file cannot be read");
        assertRefused(args(evidence, dir.resolve("missing-trust.json"), ATTEMPT, DATASET, TARGET, verdictFile),
                RecoveryReplayExit.REFUSED, "trust document cannot be read");
        assertTargetUntouched();
    }

    private RecoveryReplayPreflight.Plan check(String[] arguments, RecoveryReplayVerdict verdict) {
        return RecoveryFixtures.preflight(target).check(environment, RecoveryReplayArguments.parse(arguments), verdict);
    }

    private void refused(RecoveryReplayExit exit, String reason) {
        assertRefused(validArgs(), exit, reason);
    }

    private void assertRefused(String[] arguments, RecoveryReplayExit exit, String reason) {
        assertThatThrownBy(() -> check(arguments, verdict()))
                .isInstanceOfSatisfying(RecoveryReplayRefusal.class, refusal -> {
                    assertThat(refusal.exit()).isEqualTo(exit);
                    assertThat(refusal.getMessage()).isEqualTo(reason);
                });
    }

    private void assertTargetUntouched() {
        assertThat(target.identityReads).isZero();
        assertThat(target.anchorReads).isZero();
    }

    private RecoveryReplayVerdict verdict() {
        return new RecoveryReplayVerdict(RecoveryReplayArguments.parse(validArgs()));
    }

    private String[] validArgs() {
        return args(evidence, trust, ATTEMPT, DATASET, TARGET, verdictFile);
    }
}
