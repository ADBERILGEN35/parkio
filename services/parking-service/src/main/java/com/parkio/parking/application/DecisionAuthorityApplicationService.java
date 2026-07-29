package com.parkio.parking.application;

import com.parkio.parking.application.port.DecisionAuthorityObserverPort;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.application.result.AiValidationApplyOutcome;
import com.parkio.parking.application.result.ControlledAuthorityApplyResult;
import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.assessment.RiskAssessment;
import com.parkio.parking.decision.audit.DecisionAuditRecord;
import com.parkio.parking.decision.audit.DecisionAuditRecordFactory;
import com.parkio.parking.decision.authority.AuthorityDispositionCompatibility;
import com.parkio.parking.decision.authority.AuthorityEligibilityReason;
import com.parkio.parking.decision.authority.AuthorityFallbackReason;
import com.parkio.parking.decision.authority.AuthorityTransitionClass;
import com.parkio.parking.decision.authority.DecisionAuthoritySelection;
import com.parkio.parking.decision.authority.DecisionAuthoritySelector;
import com.parkio.parking.decision.calibration.DecisivePolicyRule;
import com.parkio.parking.decision.calibration.EvidenceAvailabilityClassifier;
import com.parkio.parking.decision.calibration.EvidenceAvailabilityProfile;
import com.parkio.parking.decision.calibration.HardConstraintFamily;
import com.parkio.parking.decision.calibration.HardConstraintFamilyClassifier;
import com.parkio.parking.decision.calibration.RiskBand;
import com.parkio.parking.decision.calibration.RiskBandClassifier;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.normalization.AiValidationEvidenceInput;
import com.parkio.parking.decision.normalization.EvidenceCollectionRequest;
import com.parkio.parking.decision.normalization.ParkingSpotEvidenceContext;
import com.parkio.parking.decision.policy.DecisionEngine;
import com.parkio.parking.decision.policy.HardConstraintPolicy;
import com.parkio.parking.decision.policy.HardConstraintResult;
import com.parkio.parking.decision.policy.ShadowDecisionPolicyConfig;
import com.parkio.parking.decision.port.DecisionAuditPort;
import com.parkio.parking.decision.port.EvidenceCollectionPort;
import com.parkio.parking.domain.ModerationPolicy;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Controlled Decision Engine authority orchestration (WP-05.8).
 *
 * <p>Default-off. When not selected, delegates to legacy
 * {@link ParkingApplicationService#applyAiValidationResult}. When selected and
 * disposition-compatible, appends a required AUTHORITATIVE audit row then applies
 * through existing aggregate publication methods in the same transaction.
 *
 * <p>Authoritative audit failures are rethrown (no silent legacy fallback after
 * potential flush). Engine/evidence/disposition failures before mutation fall back
 * to legacy safely.
 */
@Service
@Transactional
public class DecisionAuthorityApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DecisionAuthorityApplicationService.class);
    private static final HardConstraintPolicy HARD_CONSTRAINTS =
            new HardConstraintPolicy(ShadowDecisionPolicyConfig.referenceV1());

    private final DecisionAuthoritySettings settings;
    private final ParkingApplicationService parking;
    private final ParkingSpotRepository spots;
    private final DecisionEngine engine;
    private final EvidenceCollectionPort evidenceCollection;
    private final DecisionAuditPort auditPort;
    private final DecisionAuthorityObserverPort observer;
    private final ModerationPolicy moderationPolicy;
    private final Clock clock;

    public DecisionAuthorityApplicationService(
            DecisionAuthoritySettings settings,
            ParkingApplicationService parking,
            ParkingSpotRepository spots,
            DecisionEngine engine,
            EvidenceCollectionPort evidenceCollection,
            DecisionAuditPort auditPort,
            DecisionAuthorityObserverPort observer,
            ModerationPolicy moderationPolicy,
            Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.parking = Objects.requireNonNull(parking, "parking");
        this.spots = Objects.requireNonNull(spots, "spots");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.evidenceCollection = Objects.requireNonNull(evidenceCollection, "evidenceCollection");
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.moderationPolicy = Objects.requireNonNull(moderationPolicy, "moderationPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ControlledAuthorityApplyResult applyAiValidation(
            UUID parkingSpotId,
            String statusName,
            List<String> detectedRiskTypes,
            UUID evaluationId,
            Instant occurredAt,
            AiValidationEvidenceInput evidenceInput) {
        long started = System.nanoTime();
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(evaluationId, "evaluationId");

        ParkingSpot spot = spots.findById(parkingSpotId)
                .orElseThrow(() -> new ParkingException(ParkingErrorCode.SPOT_NOT_FOUND));

        Optional<DecisionAuditRecord> existing = auditPort.findAuthoritativeApplied(
                evaluationId, settings.policyVersion());
        if (existing.isPresent()) {
            ParkingSpotStatus current = spot.status();
            AiValidationApplyOutcome noop = new AiValidationApplyOutcome(
                    current, current, AiValidationApplyOutcome.Kind.NO_CHANGE);
            observer.recordConsidered(AuthorityEligibilityReason.IDEMPOTENT_ALREADY_APPLIED);
            observer.recordDuration(elapsed(started));
            return ControlledAuthorityApplyResult.authorityApplied(
                    noop, AuthorityEligibilityReason.IDEMPOTENT_ALREADY_APPLIED);
        }

        boolean stale = spot.isStaleModerationEvent(occurredAt);

        DecisionAuthoritySelection selection = DecisionAuthoritySelector.select(
                settings.enabled(),
                settings.canaryPercentage(),
                settings.policyVersion(),
                parkingSpotId,
                evaluationId,
                spot.status(),
                stale);

        observer.recordConsidered(selection.reason());

        if (!selection.selected()) {
            AiValidationApplyOutcome legacy = parking.applyAiValidationResult(
                    parkingSpotId, statusName, detectedRiskTypes, evaluationId, occurredAt);
            AuthorityFallbackReason fallback = mapNonSelectedFallback(selection.reason());
            if (selection.reason() != AuthorityEligibilityReason.AUTHORITY_DISABLED
                    && selection.reason() != AuthorityEligibilityReason.ZERO_PERCENT_CANARY) {
                observer.recordFallback(fallback);
            }
            observer.recordDuration(elapsed(started));
            return ControlledAuthorityApplyResult.legacy(legacy, selection.reason(), fallback);
        }

        observer.recordSelected();

        EvidenceVector vector;
        DecisionResult decision;
        EvaluationContext context;
        Instant now = clock.instant();
        try {
            vector = collectEvidence(spot, evidenceInput);
            context = EvaluationContext.of(
                    ShadowDecisionPolicyConfig.POLICY_VERSION,
                    now,
                    "runtime-authority");
            decision = engine.evaluate(vector, context);
        } catch (RuntimeException ex) {
            observer.recordEngineFailure();
            observer.recordFallback(AuthorityFallbackReason.ENGINE_FAILURE);
            if (log.isDebugEnabled()) {
                log.debug(
                        "Authority engine/evidence failed; legacy fallback spotId={} evaluationId={}",
                        parkingSpotId,
                        evaluationId,
                        ex);
            }
            AiValidationApplyOutcome legacy = parking.applyAiValidationResult(
                    parkingSpotId, statusName, detectedRiskTypes, evaluationId, occurredAt);
            observer.recordDuration(elapsed(started));
            return ControlledAuthorityApplyResult.legacy(
                    legacy,
                    AuthorityEligibilityReason.ELIGIBLE_SELECTED,
                    AuthorityFallbackReason.ENGINE_FAILURE);
        }

        EvidenceAvailabilityProfile profile = EvidenceAvailabilityClassifier.from(vector);
        if (profile != EvidenceAvailabilityProfile.COMPLETE_CURRENT_V1) {
            observer.recordFallback(AuthorityFallbackReason.EVIDENCE_INCOMPLETE);
            AiValidationApplyOutcome legacy = parking.applyAiValidationResult(
                    parkingSpotId, statusName, detectedRiskTypes, evaluationId, occurredAt);
            observer.recordDuration(elapsed(started));
            return ControlledAuthorityApplyResult.legacy(
                    legacy,
                    AuthorityEligibilityReason.INSUFFICIENT_EVIDENCE_PROFILE,
                    AuthorityFallbackReason.EVIDENCE_INCOMPLETE);
        }

        RiskAssessment risk = decision.assessment().riskAssessment().orElse(null);
        if (risk != null && risk.hardConstraintActive()) {
            observer.recordFallback(AuthorityFallbackReason.HARD_CONSTRAINT);
            AiValidationApplyOutcome legacy = parking.applyAiValidationResult(
                    parkingSpotId, statusName, detectedRiskTypes, evaluationId, occurredAt);
            observer.recordDuration(elapsed(started));
            return ControlledAuthorityApplyResult.legacy(
                    legacy,
                    AuthorityEligibilityReason.HARD_CONSTRAINT_ACTIVE,
                    AuthorityFallbackReason.HARD_CONSTRAINT);
        }

        PublicationDisposition disposition = decision.disposition();
        AuthorityTransitionClass transition = AuthorityDispositionCompatibility.classify(
                spot.status(), disposition);
        if (transition != AuthorityTransitionClass.APPLY_SUPPORTED
                || !AuthorityDispositionCompatibility.isCanaryAuthorityDisposition(disposition)) {
            observer.recordFallback(AuthorityFallbackReason.UNSUPPORTED_DISPOSITION);
            AiValidationApplyOutcome legacy = parking.applyAiValidationResult(
                    parkingSpotId, statusName, detectedRiskTypes, evaluationId, occurredAt);
            observer.recordDuration(elapsed(started));
            return ControlledAuthorityApplyResult.legacy(
                    legacy,
                    AuthorityEligibilityReason.UNSUPPORTED_DISPOSITION,
                    AuthorityFallbackReason.UNSUPPORTED_DISPOSITION);
        }

        ParkingSpotStatus expectedApplied = moderationPolicy.isStillPublishable(spot.createdAt(), now)
                ? ParkingSpotStatus.ACTIVE
                : ParkingSpotStatus.REVIEW_FAILED;
        int canaryBucket = selection.canaryBucket().orElseThrow();

        HardConstraintResult hard = decision.assessment().assessmentBundle()
                .map(HARD_CONSTRAINTS::evaluate)
                .orElse(HardConstraintResult.inactive(ShadowDecisionPolicyConfig.POLICY_VERSION));
        RiskBand riskBand = risk == null ? RiskBand.UNKNOWN : RiskBandClassifier.from(risk);
        HardConstraintFamily family = HardConstraintFamilyClassifier.from(hard);
        DecisivePolicyRule decisiveRule = decision.decisiveRule();

        try {
            DecisionAuditRecord audit = DecisionAuditRecordFactory.fromAuthoritativeApply(
                    UUID.randomUUID(),
                    vector,
                    context,
                    decision,
                    riskBand,
                    family,
                    profile,
                    decisiveRule,
                    spot.status(),
                    expectedApplied,
                    canaryBucket,
                    now);
            auditPort.appendAuthoritativeRequired(audit);
        } catch (RuntimeException ex) {
            observer.recordAuditFailure();
            observer.recordFallback(AuthorityFallbackReason.AUDIT_FAILURE);
            observer.recordDuration(elapsed(started));
            // Do not fall back to legacy after an audit write may have flushed —
            // fail the transaction for retry.
            throw ex;
        }

        AiValidationApplyOutcome applied;
        try {
            applied = parking.applyAuthoritativeFullPublish(parkingSpotId, evaluationId, occurredAt);
        } catch (RuntimeException ex) {
            observer.recordFallback(AuthorityFallbackReason.TRANSITION_CONFLICT);
            observer.recordDuration(elapsed(started));
            throw ex;
        }

        if (applied.kind() != AiValidationApplyOutcome.Kind.APPLIED
                || applied.resultingStatus() != expectedApplied) {
            observer.recordFallback(AuthorityFallbackReason.TRANSITION_CONFLICT);
            observer.recordDuration(elapsed(started));
            throw new IllegalStateException(
                    "Authoritative apply outcome mismatch; rolling back audit and mutation");
        }

        observer.recordApplied(disposition, applied.resultingStatus());
        observer.recordDuration(elapsed(started));
        return ControlledAuthorityApplyResult.authorityApplied(
                applied, AuthorityEligibilityReason.ELIGIBLE_SELECTED);
    }

    private EvidenceVector collectEvidence(ParkingSpot spot, AiValidationEvidenceInput evidenceInput) {
        Objects.requireNonNull(evidenceInput, "evidenceInput");
        ParkingSpotEvidenceContext spotContext = new ParkingSpotEvidenceContext(
                spot.id(),
                spot.mediaId(),
                spot.latitude(),
                spot.longitude(),
                spot.legalStatus().name(),
                spot.manualLocationEdited(),
                spot.moderationDecidedAt());
        EvidenceCollectionRequest request = new EvidenceCollectionRequest(
                spot.id(),
                evidenceInput.eventId(),
                evidenceInput.occurredAt(),
                evidenceInput,
                spotContext);
        return evidenceCollection.collect(request);
    }

    private static AuthorityFallbackReason mapNonSelectedFallback(AuthorityEligibilityReason reason) {
        return switch (reason) {
            case AUTHORITY_DISABLED, ZERO_PERCENT_CANARY, NOT_SELECTED -> AuthorityFallbackReason.NOT_SELECTED;
            case CONFIGURATION_INVALID, UNSUPPORTED_POLICY_VERSION -> AuthorityFallbackReason.CONFIGURATION_FAILURE;
            case STALE_EVENT, ALREADY_FINALIZED, MODERATOR_CONTROLLED, UNSUPPORTED_CURRENT_STATUS ->
                    AuthorityFallbackReason.LEGACY_REQUIRED;
            default -> AuthorityFallbackReason.LEGACY_REQUIRED;
        };
    }

    private static Duration elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }
}