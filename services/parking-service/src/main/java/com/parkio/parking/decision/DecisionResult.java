package com.parkio.parking.decision;

import com.parkio.parking.decision.assessment.DerivedAssessment;
import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.calibration.DecisivePolicyRule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable Decision Engine output for one ParkingSpot evaluation.
 *
 * <p>Explainable via machine-readable {@link ReasonCode}s, bounded
 * {@link DecisivePolicyRule}, and policy version. Contains no side effects.
 */
public final class DecisionResult {

    private final UUID parkingSpotId;
    private final UUID evaluationId;
    private final PublicationDisposition disposition;
    private final DerivedAssessment assessment;
    private final List<ReasonCode> reasonCodes;
    private final DecisivePolicyRule decisiveRule;
    private final String policyVersion;
    private final Instant decidedAt;
    private final boolean asynchronousFollowUpRequired;

    private DecisionResult(
            UUID parkingSpotId,
            UUID evaluationId,
            PublicationDisposition disposition,
            DerivedAssessment assessment,
            List<ReasonCode> reasonCodes,
            DecisivePolicyRule decisiveRule,
            String policyVersion,
            Instant decidedAt,
            boolean asynchronousFollowUpRequired) {
        this.parkingSpotId = Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        this.evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
        this.disposition = Objects.requireNonNull(disposition, "disposition");
        this.assessment = Objects.requireNonNull(assessment, "assessment");
        this.reasonCodes = copyReasons(reasonCodes);
        this.decisiveRule = Objects.requireNonNull(decisiveRule, "decisiveRule");
        this.policyVersion = requirePolicyVersion(policyVersion);
        this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        this.asynchronousFollowUpRequired = asynchronousFollowUpRequired;
        if (this.reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("DecisionResult requires at least one reason code");
        }
    }

    public static DecisionResult of(
            UUID parkingSpotId,
            UUID evaluationId,
            PublicationDisposition disposition,
            DerivedAssessment assessment,
            List<ReasonCode> reasonCodes,
            DecisivePolicyRule decisiveRule,
            String policyVersion,
            Instant decidedAt,
            boolean asynchronousFollowUpRequired) {
        return new DecisionResult(
                parkingSpotId,
                evaluationId,
                disposition,
                assessment,
                reasonCodes,
                decisiveRule,
                policyVersion,
                decidedAt,
                asynchronousFollowUpRequired);
    }

    public UUID parkingSpotId() {
        return parkingSpotId;
    }

    public UUID evaluationId() {
        return evaluationId;
    }

    public PublicationDisposition disposition() {
        return disposition;
    }

    public DerivedAssessment assessment() {
        return assessment;
    }

    public List<ReasonCode> reasonCodes() {
        return reasonCodes;
    }

    public DecisivePolicyRule decisiveRule() {
        return decisiveRule;
    }

    public String policyVersion() {
        return policyVersion;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    public boolean asynchronousFollowUpRequired() {
        return asynchronousFollowUpRequired;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DecisionResult that)) {
            return false;
        }
        return asynchronousFollowUpRequired == that.asynchronousFollowUpRequired
                && parkingSpotId.equals(that.parkingSpotId)
                && evaluationId.equals(that.evaluationId)
                && disposition == that.disposition
                && assessment.equals(that.assessment)
                && reasonCodes.equals(that.reasonCodes)
                && decisiveRule == that.decisiveRule
                && policyVersion.equals(that.policyVersion)
                && decidedAt.equals(that.decidedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                parkingSpotId,
                evaluationId,
                disposition,
                assessment,
                reasonCodes,
                decisiveRule,
                policyVersion,
                decidedAt,
                asynchronousFollowUpRequired);
    }

    private static String requirePolicyVersion(String policyVersion) {
        Objects.requireNonNull(policyVersion, "policyVersion");
        String trimmed = policyVersion.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("policyVersion must not be blank");
        }
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("policyVersion must be at most 64 characters");
        }
        return trimmed;
    }

    private static List<ReasonCode> copyReasons(List<ReasonCode> reasonCodes) {
        Objects.requireNonNull(reasonCodes, "reasonCodes");
        List<ReasonCode> copy = new ArrayList<>(reasonCodes.size());
        for (ReasonCode code : reasonCodes) {
            if (code == null) {
                throw new IllegalArgumentException("reasonCodes must not contain null");
            }
            copy.add(code);
        }
        return Collections.unmodifiableList(copy);
    }
}