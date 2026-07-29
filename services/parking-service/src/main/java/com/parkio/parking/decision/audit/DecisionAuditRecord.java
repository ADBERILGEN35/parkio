package com.parkio.parking.decision.audit;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.authority.AuthorityAlgorithmVersion;
import com.parkio.parking.decision.authority.DecisionExecutionMode;
import com.parkio.parking.decision.calibration.DecisivePolicyRule;
import com.parkio.parking.decision.calibration.EvidenceAvailabilityProfile;
import com.parkio.parking.decision.calibration.HardConstraintFamily;
import com.parkio.parking.decision.calibration.RiskBand;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.shadow.LegacyPublicationOutcome;
import com.parkio.parking.decision.shadow.ShadowComparisonCategory;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Immutable aggregate snapshot of one completed Decision Engine evaluation
 * (shadow or authoritative). Append-only conceptually.
 */
public final class DecisionAuditRecord {

    private final UUID auditId;
    private final UUID parkingSpotId;
    private final UUID evaluationId;
    private final String policyVersion;
    private final String decisionEngineVersion;
    private final String shadowModeVersion;
    private final Instant evaluatedAt;
    private final EvidenceVector evidence;
    private final EvaluationContext evaluationContext;
    private final DecisionResult decision;
    private final LegacyPublicationOutcome legacyOutcome;
    private final ShadowComparisonCategory comparisonCategory;
    private final RiskBand riskBand;
    private final HardConstraintFamily hardConstraintFamily;
    private final EvidenceAvailabilityProfile evidenceProfile;
    private final DecisivePolicyRule decisiveRule;
    private final Instant createdAt;
    private final DecisionExecutionMode executionMode;
    private final String authorityAlgorithmVersion;
    private final Integer canaryBucket;
    private final boolean authorityApplied;
    private final ParkingSpotStatus appliedStatus;

    private DecisionAuditRecord(
            UUID auditId,
            UUID parkingSpotId,
            UUID evaluationId,
            String policyVersion,
            String decisionEngineVersion,
            String shadowModeVersion,
            Instant evaluatedAt,
            EvidenceVector evidence,
            EvaluationContext evaluationContext,
            DecisionResult decision,
            LegacyPublicationOutcome legacyOutcome,
            ShadowComparisonCategory comparisonCategory,
            RiskBand riskBand,
            HardConstraintFamily hardConstraintFamily,
            EvidenceAvailabilityProfile evidenceProfile,
            DecisivePolicyRule decisiveRule,
            Instant createdAt,
            DecisionExecutionMode executionMode,
            String authorityAlgorithmVersion,
            Integer canaryBucket,
            boolean authorityApplied,
            ParkingSpotStatus appliedStatus) {
        this.auditId = Objects.requireNonNull(auditId, "auditId");
        this.parkingSpotId = Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        this.evaluationId = Objects.requireNonNull(evaluationId, "evaluationId");
        this.policyVersion = requireBounded(policyVersion, "policyVersion");
        this.decisionEngineVersion = requireBounded(decisionEngineVersion, "decisionEngineVersion");
        this.shadowModeVersion = requireBounded(shadowModeVersion, "shadowModeVersion");
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.evaluationContext = Objects.requireNonNull(evaluationContext, "evaluationContext");
        this.decision = Objects.requireNonNull(decision, "decision");
        this.legacyOutcome = Objects.requireNonNull(legacyOutcome, "legacyOutcome");
        this.comparisonCategory = Objects.requireNonNull(comparisonCategory, "comparisonCategory");
        this.riskBand = Objects.requireNonNull(riskBand, "riskBand");
        this.hardConstraintFamily = Objects.requireNonNull(hardConstraintFamily, "hardConstraintFamily");
        this.evidenceProfile = Objects.requireNonNull(evidenceProfile, "evidenceProfile");
        this.decisiveRule = Objects.requireNonNull(decisiveRule, "decisiveRule");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        this.authorityAlgorithmVersion = authorityAlgorithmVersion == null
                ? null
                : requireBounded(authorityAlgorithmVersion, "authorityAlgorithmVersion");
        this.canaryBucket = canaryBucket;
        this.authorityApplied = authorityApplied;
        this.appliedStatus = appliedStatus;

        if (!parkingSpotId.equals(evidence.parkingSpotId()) || !parkingSpotId.equals(decision.parkingSpotId())) {
            throw new IllegalArgumentException("parkingSpotId must match evidence and decision");
        }
        if (!evaluationId.equals(evidence.evaluationId()) || !evaluationId.equals(decision.evaluationId())) {
            throw new IllegalArgumentException("evaluationId must match evidence and decision");
        }
        if (!policyVersion.equals(decision.policyVersion())) {
            throw new IllegalArgumentException("policyVersion must match DecisionResult.policyVersion");
        }
        if (!policyVersion.equals(evaluationContext.evaluationPolicyVersion().value())) {
            throw new IllegalArgumentException("policyVersion must match EvaluationContext");
        }
        if (decisiveRule != decision.decisiveRule()) {
            throw new IllegalArgumentException("decisiveRule must match DecisionResult.decisiveRule");
        }
        if (executionMode == DecisionExecutionMode.SHADOW && authorityApplied) {
            throw new IllegalArgumentException("SHADOW records cannot be authorityApplied");
        }
        if (executionMode == DecisionExecutionMode.AUTHORITATIVE && !authorityApplied) {
            throw new IllegalArgumentException("AUTHORITATIVE records must set authorityApplied");
        }
        if (authorityApplied && appliedStatus == null) {
            throw new IllegalArgumentException("authorityApplied requires appliedStatus");
        }
        if (canaryBucket != null && (canaryBucket < 0 || canaryBucket >= 10_000)) {
            throw new IllegalArgumentException("canaryBucket must be in [0, 9999]");
        }
    }

    /** Shadow convenience factory (WP-05.7 compatible). */
    public static DecisionAuditRecord of(
            UUID auditId,
            UUID parkingSpotId,
            UUID evaluationId,
            String policyVersion,
            String decisionEngineVersion,
            String shadowModeVersion,
            Instant evaluatedAt,
            EvidenceVector evidence,
            EvaluationContext evaluationContext,
            DecisionResult decision,
            LegacyPublicationOutcome legacyOutcome,
            ShadowComparisonCategory comparisonCategory,
            RiskBand riskBand,
            HardConstraintFamily hardConstraintFamily,
            EvidenceAvailabilityProfile evidenceProfile,
            DecisivePolicyRule decisiveRule,
            Instant createdAt) {
        return of(
                auditId,
                parkingSpotId,
                evaluationId,
                policyVersion,
                decisionEngineVersion,
                shadowModeVersion,
                evaluatedAt,
                evidence,
                evaluationContext,
                decision,
                legacyOutcome,
                comparisonCategory,
                riskBand,
                hardConstraintFamily,
                evidenceProfile,
                decisiveRule,
                createdAt,
                DecisionExecutionMode.SHADOW,
                null,
                null,
                false,
                null);
    }

    public static DecisionAuditRecord of(
            UUID auditId,
            UUID parkingSpotId,
            UUID evaluationId,
            String policyVersion,
            String decisionEngineVersion,
            String shadowModeVersion,
            Instant evaluatedAt,
            EvidenceVector evidence,
            EvaluationContext evaluationContext,
            DecisionResult decision,
            LegacyPublicationOutcome legacyOutcome,
            ShadowComparisonCategory comparisonCategory,
            RiskBand riskBand,
            HardConstraintFamily hardConstraintFamily,
            EvidenceAvailabilityProfile evidenceProfile,
            DecisivePolicyRule decisiveRule,
            Instant createdAt,
            DecisionExecutionMode executionMode,
            String authorityAlgorithmVersion,
            Integer canaryBucket,
            boolean authorityApplied,
            ParkingSpotStatus appliedStatus) {
        return new DecisionAuditRecord(
                auditId,
                parkingSpotId,
                evaluationId,
                policyVersion,
                decisionEngineVersion,
                shadowModeVersion,
                evaluatedAt,
                evidence,
                evaluationContext,
                decision,
                legacyOutcome,
                comparisonCategory,
                riskBand,
                hardConstraintFamily,
                evidenceProfile,
                decisiveRule,
                createdAt,
                executionMode,
                authorityAlgorithmVersion,
                canaryBucket,
                authorityApplied,
                appliedStatus);
    }

    public DecisionReplayInput toReplayInput() {
        return DecisionReplayInput.of(evidence, evaluationContext);
    }

    public UUID auditId() { return auditId; }
    public UUID parkingSpotId() { return parkingSpotId; }
    public UUID evaluationId() { return evaluationId; }
    public String policyVersion() { return policyVersion; }
    public String decisionEngineVersion() { return decisionEngineVersion; }
    public String shadowModeVersion() { return shadowModeVersion; }
    public Instant evaluatedAt() { return evaluatedAt; }
    public EvidenceVector evidence() { return evidence; }
    public EvaluationContext evaluationContext() { return evaluationContext; }
    public DecisionResult decision() { return decision; }
    public LegacyPublicationOutcome legacyOutcome() { return legacyOutcome; }
    public ShadowComparisonCategory comparisonCategory() { return comparisonCategory; }
    public RiskBand riskBand() { return riskBand; }
    public HardConstraintFamily hardConstraintFamily() { return hardConstraintFamily; }
    public EvidenceAvailabilityProfile evidenceProfile() { return evidenceProfile; }
    public DecisivePolicyRule decisiveRule() { return decisiveRule; }
    public PublicationDisposition disposition() { return decision.disposition(); }
    public Instant createdAt() { return createdAt; }
    public DecisionExecutionMode executionMode() { return executionMode; }
    public Optional<String> authorityAlgorithmVersion() {
        return Optional.ofNullable(authorityAlgorithmVersion);
    }
    public OptionalInt canaryBucket() {
        return canaryBucket == null ? OptionalInt.empty() : OptionalInt.of(canaryBucket);
    }
    public boolean authorityApplied() { return authorityApplied; }
    public Optional<ParkingSpotStatus> appliedStatus() { return Optional.ofNullable(appliedStatus); }

    private static String requireBounded(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException(field + " must be at most 64 characters");
        }
        return trimmed;
    }
}