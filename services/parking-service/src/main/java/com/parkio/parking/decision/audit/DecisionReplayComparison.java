package com.parkio.parking.decision.audit;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.calibration.DecisivePolicyRule;
import java.util.Objects;

/**
 * Offline comparison between an original audited {@link DecisionResult} and a replayed result.
 */
public final class DecisionReplayComparison {

    private final DecisionResult original;
    private final DecisionResult replayed;
    private final boolean identical;

    private DecisionReplayComparison(DecisionResult original, DecisionResult replayed, boolean identical) {
        this.original = Objects.requireNonNull(original, "original");
        this.replayed = Objects.requireNonNull(replayed, "replayed");
        this.identical = identical;
    }

    public static DecisionReplayComparison of(DecisionResult original, DecisionResult replayed) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(replayed, "replayed");
        boolean identical = original.equals(replayed);
        return new DecisionReplayComparison(original, replayed, identical);
    }

    public DecisionResult original() {
        return original;
    }

    public DecisionResult replayed() {
        return replayed;
    }

    public boolean identical() {
        return identical;
    }

    public boolean dispositionUnchanged() {
        return original.disposition() == replayed.disposition();
    }

    public boolean decisiveRuleUnchanged() {
        return original.decisiveRule() == replayed.decisiveRule();
    }

    public PublicationDisposition originalDisposition() {
        return original.disposition();
    }

    public PublicationDisposition replayedDisposition() {
        return replayed.disposition();
    }

    public DecisivePolicyRule originalDecisiveRule() {
        return original.decisiveRule();
    }

    public DecisivePolicyRule replayedDecisiveRule() {
        return replayed.decisiveRule();
    }
}