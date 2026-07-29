package com.parkio.parking.decision.calibration;

import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.shadow.LegacyPublicationOutcome;
import com.parkio.parking.decision.shadow.ShadowComparisonCategory;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable structured observation for one successful non-authoritative shadow evaluation.
 * Safe for bounded Micrometer tagging — contains no spot/event IDs or raw payloads.
 */
public final class DecisionCalibrationObservation {

    private final String policyVersion;
    private final LegacyPublicationOutcome.Kind legacyKind;
    private final ParkingSpotStatus legacyStatus;
    private final PublicationDisposition shadowDisposition;
    private final ShadowComparisonCategory comparisonCategory;
    private final RiskBand riskBand;
    private final HardConstraintFamily hardConstraintFamily;
    private final DecisivePolicyRule decisiveRule;
    private final EvidenceAvailabilityProfile evidenceProfile;
    private final List<AssessmentCategorySnapshot> assessments;
    private final Duration orchestrationDuration;
    private final Instant observedAt;

    private DecisionCalibrationObservation(
            String policyVersion,
            LegacyPublicationOutcome.Kind legacyKind,
            ParkingSpotStatus legacyStatus,
            PublicationDisposition shadowDisposition,
            ShadowComparisonCategory comparisonCategory,
            RiskBand riskBand,
            HardConstraintFamily hardConstraintFamily,
            DecisivePolicyRule decisiveRule,
            EvidenceAvailabilityProfile evidenceProfile,
            List<AssessmentCategorySnapshot> assessments,
            Duration orchestrationDuration,
            Instant observedAt) {
        this.policyVersion = requirePolicyVersion(policyVersion);
        this.legacyKind = Objects.requireNonNull(legacyKind, "legacyKind");
        this.legacyStatus = Objects.requireNonNull(legacyStatus, "legacyStatus");
        this.shadowDisposition = Objects.requireNonNull(shadowDisposition, "shadowDisposition");
        this.comparisonCategory = Objects.requireNonNull(comparisonCategory, "comparisonCategory");
        this.riskBand = Objects.requireNonNull(riskBand, "riskBand");
        this.hardConstraintFamily = Objects.requireNonNull(hardConstraintFamily, "hardConstraintFamily");
        this.decisiveRule = Objects.requireNonNull(decisiveRule, "decisiveRule");
        this.evidenceProfile = Objects.requireNonNull(evidenceProfile, "evidenceProfile");
        this.assessments = List.copyOf(assessments);
        this.orchestrationDuration = Objects.requireNonNull(orchestrationDuration, "orchestrationDuration");
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
        if (orchestrationDuration.isNegative()) {
            throw new IllegalArgumentException("orchestrationDuration must not be negative");
        }
    }

    public static DecisionCalibrationObservation of(
            String policyVersion,
            LegacyPublicationOutcome.Kind legacyKind,
            ParkingSpotStatus legacyStatus,
            PublicationDisposition shadowDisposition,
            ShadowComparisonCategory comparisonCategory,
            RiskBand riskBand,
            HardConstraintFamily hardConstraintFamily,
            DecisivePolicyRule decisiveRule,
            EvidenceAvailabilityProfile evidenceProfile,
            List<AssessmentCategorySnapshot> assessments,
            Duration orchestrationDuration,
            Instant observedAt) {
        return new DecisionCalibrationObservation(
                policyVersion,
                legacyKind,
                legacyStatus,
                shadowDisposition,
                comparisonCategory,
                riskBand,
                hardConstraintFamily,
                decisiveRule,
                evidenceProfile,
                assessments,
                orchestrationDuration,
                observedAt);
    }

    public String policyVersion() {
        return policyVersion;
    }

    public LegacyPublicationOutcome.Kind legacyKind() {
        return legacyKind;
    }

    public ParkingSpotStatus legacyStatus() {
        return legacyStatus;
    }

    public PublicationDisposition shadowDisposition() {
        return shadowDisposition;
    }

    public ShadowComparisonCategory comparisonCategory() {
        return comparisonCategory;
    }

    public RiskBand riskBand() {
        return riskBand;
    }

    public HardConstraintFamily hardConstraintFamily() {
        return hardConstraintFamily;
    }

    public DecisivePolicyRule decisiveRule() {
        return decisiveRule;
    }

    public EvidenceAvailabilityProfile evidenceProfile() {
        return evidenceProfile;
    }

    public List<AssessmentCategorySnapshot> assessments() {
        return assessments;
    }

    public Duration orchestrationDuration() {
        return orchestrationDuration;
    }

    public Instant observedAt() {
        return observedAt;
    }

    /** True when comparison category is a safe comparable posture (excludes NOT_COMPARABLE / NO_SAFE_EQUIVALENCE). */
    public boolean comparable() {
        return comparisonCategory == ShadowComparisonCategory.EQUIVALENT
                || comparisonCategory == ShadowComparisonCategory.SHADOW_MORE_RESTRICTIVE
                || comparisonCategory == ShadowComparisonCategory.SHADOW_MORE_PERMISSIVE
                || comparisonCategory == ShadowComparisonCategory.LEGACY_REVIEW_SHADOW_HOLD;
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
}
