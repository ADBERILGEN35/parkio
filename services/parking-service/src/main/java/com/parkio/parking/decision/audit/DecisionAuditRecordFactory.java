package com.parkio.parking.decision.audit;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.authority.AuthorityAlgorithmVersion;
import com.parkio.parking.decision.authority.DecisionExecutionMode;
import com.parkio.parking.decision.calibration.DecisionCalibrationObservation;
import com.parkio.parking.decision.calibration.DecisivePolicyRule;
import com.parkio.parking.decision.calibration.EvidenceAvailabilityProfile;
import com.parkio.parking.decision.calibration.HardConstraintFamily;
import com.parkio.parking.decision.calibration.RiskBand;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.shadow.LegacyPublicationOutcome;
import com.parkio.parking.decision.shadow.ShadowComparisonCategory;
import com.parkio.parking.decision.shadow.ShadowDecisionComparison;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Builds immutable {@link DecisionAuditRecord} values for shadow or authoritative modes. */
public final class DecisionAuditRecordFactory {

    private DecisionAuditRecordFactory() {}

    public static DecisionAuditRecord fromSuccessfulShadow(
            UUID auditId,
            EvidenceVector evidence,
            EvaluationContext context,
            DecisionResult decision,
            ShadowDecisionComparison comparison,
            DecisionCalibrationObservation observation,
            Instant createdAt) {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(createdAt, "createdAt");

        LegacyPublicationOutcome legacy = comparison.legacy();
        return DecisionAuditRecord.of(
                auditId,
                evidence.parkingSpotId(),
                evidence.evaluationId(),
                decision.policyVersion(),
                DecisionEngineVersion.V1,
                ShadowModeVersion.V1,
                context.evaluatedAt(),
                evidence,
                context,
                decision,
                legacy,
                comparison.category(),
                observation.riskBand(),
                observation.hardConstraintFamily(),
                observation.evidenceProfile(),
                observation.decisiveRule(),
                createdAt,
                DecisionExecutionMode.SHADOW,
                null,
                null,
                false,
                null);
    }

    public static DecisionAuditRecord fromAuthoritativeApply(
            UUID auditId,
            EvidenceVector evidence,
            EvaluationContext context,
            DecisionResult decision,
            RiskBand riskBand,
            HardConstraintFamily hardConstraintFamily,
            EvidenceAvailabilityProfile evidenceProfile,
            DecisivePolicyRule decisiveRule,
            ParkingSpotStatus previousStatus,
            ParkingSpotStatus appliedStatus,
            int canaryBucket,
            Instant createdAt) {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(riskBand, "riskBand");
        Objects.requireNonNull(hardConstraintFamily, "hardConstraintFamily");
        Objects.requireNonNull(evidenceProfile, "evidenceProfile");
        Objects.requireNonNull(decisiveRule, "decisiveRule");
        Objects.requireNonNull(previousStatus, "previousStatus");
        Objects.requireNonNull(appliedStatus, "appliedStatus");
        Objects.requireNonNull(createdAt, "createdAt");

        LegacyPublicationOutcome placeholder = new LegacyPublicationOutcome(
                previousStatus, previousStatus, LegacyPublicationOutcome.Kind.NO_CHANGE);
        return DecisionAuditRecord.of(
                auditId,
                evidence.parkingSpotId(),
                evidence.evaluationId(),
                decision.policyVersion(),
                DecisionEngineVersion.V1,
                ShadowModeVersion.V1,
                context.evaluatedAt(),
                evidence,
                context,
                decision,
                placeholder,
                ShadowComparisonCategory.NOT_COMPARABLE,
                riskBand,
                hardConstraintFamily,
                evidenceProfile,
                decisiveRule,
                createdAt,
                DecisionExecutionMode.AUTHORITATIVE,
                AuthorityAlgorithmVersion.V1,
                canaryBucket,
                true,
                appliedStatus);
    }
}