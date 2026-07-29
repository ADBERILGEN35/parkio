package com.parkio.parking.exposure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Offline replay of frozen exposure snapshots. */
public final class ExposureReplayer {

    private final ExposureEngine engine = new ExposureEngine();

    public ExposureReplayComparison replay(ExposureSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.schemaVersion() != ExposureSnapshotSchemaVersion.V1) {
            throw new UnsupportedExposurePolicyVersionException(
                    "Unsupported exposure snapshot schema: " + snapshot.schemaVersion());
        }
        if (!ExposurePolicyConfig.POLICY_VERSION.equals(snapshot.policyVersion())) {
            throw new UnsupportedExposurePolicyVersionException(
                    "Unsupported exposure policy version: " + snapshot.policyVersion());
        }

        List<ExposureEvaluation> replayed = new ArrayList<>(snapshot.candidates().size());
        for (ExposureEvidence evidence : snapshot.candidates()) {
            replayed.add(engine.evaluate(evidence, snapshot.evaluationContext()));
        }

        ExposureComparison replayComparison = ExposureShadowOrdering.compare(
                snapshot.queryContext(),
                snapshot.policyVersion(),
                snapshot.schemaVersion(),
                snapshot.comparison().legacyOrder().stream().map(LegacySearchPosition::candidateId).toList(),
                replayed,
                snapshot.capturedAt());

        boolean identical = evaluationsMatch(snapshot.evaluations(), replayed)
                && snapshot.comparison().sameTop1() == replayComparison.sameTop1()
                && snapshot.comparison().sameTop3Order() == replayComparison.sameTop3Order()
                && snapshot.comparison().maxRankMovement() == replayComparison.maxRankMovement();

        return new ExposureReplayComparison(
                identical,
                snapshot.policyVersion(),
                snapshot.schemaVersion(),
                snapshot.candidates().size(),
                replayComparison.sameTop1(),
                identical ? null : "REPLAY_MISMATCH");
    }

    private static boolean evaluationsMatch(List<ExposureEvaluation> expected, List<ExposureEvaluation> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            ExposureEvaluation left = expected.get(i);
            ExposureEvaluation right = actual.get(i);
            if (!left.evidence().candidateId().equals(right.evidence().candidateId())) {
                return false;
            }
            if (left.eligibility() != right.eligibility()
                    || left.disposition() != right.disposition()
                    || left.score().total() != right.score().total()) {
                return false;
            }
        }
        return true;
    }
}
