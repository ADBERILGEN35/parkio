package com.parkio.parking.decision.policy;

import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidencePolarity;
import com.parkio.parking.decision.evidence.EvidenceSource;
import com.parkio.parking.decision.evidence.EvidenceType;
import com.parkio.parking.decision.evidence.EvidenceVector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Typed builders for Decision Engine golden fixtures (decision-shadow-v1). */
public final class DecisionGoldenFixtures {

    public static final Instant T0 = Instant.parse("2026-07-27T12:00:00Z");
    public static final UUID SPOT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    public static final UUID EVAL_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    public static final String EVENT_REF = "event-fixture-1";

    private DecisionGoldenFixtures() {}

    public static EvaluationContext context() {
        return EvaluationContext.of(ShadowDecisionPolicyConfig.POLICY_VERSION, T0, "golden");
    }

    public static EvidenceVector strongNormal() {
        return vector(baseStrongItems());
    }

    public static EvidenceVector conflictingLegality() {
        List<EvidenceItem> items = new ArrayList<>(baseStrongItems());
        items.add(ai(
                EvidencePolarity.OPPOSES_PUBLISH,
                80,
                "AI_RISK_NO_PARKING_SIGN"));
        return vector(items);
    }

    public static EvidenceVector poorImageQuality() {
        List<EvidenceItem> items = new ArrayList<>();
        items.add(ai(EvidencePolarity.SUPPORTS_PUBLISH, 55, "AI_STATUS_PASSED"));
        items.add(ai(EvidencePolarity.SUPPORTS_PUBLISH, 85, "EMPTY_SPACE_CONFIDENCE"));
        items.add(ai(EvidencePolarity.SUPPORTS_PUBLISH, 25, "IMAGE_QUALITY_SCORE"));
        items.add(ai(EvidencePolarity.NEUTRAL, 20, "AI_RISK_LOW_IMAGE_QUALITY"));
        items.add(legalScore(10));
        items.addAll(validLocationAndIntegrity());
        return vector(items);
    }

    public static EvidenceVector aiFailedNoHardConstraint() {
        List<EvidenceItem> items = new ArrayList<>();
        items.add(ai(EvidencePolarity.OPPOSES_PUBLISH, 75, "AI_STATUS_FAILED"));
        items.add(ai(EvidencePolarity.SUPPORTS_PUBLISH, 40, "EMPTY_SPACE_CONFIDENCE"));
        items.add(ai(EvidencePolarity.SUPPORTS_PUBLISH, 70, "IMAGE_QUALITY_SCORE"));
        items.add(legalScore(15));
        items.addAll(validLocationAndIntegrity());
        return vector(items);
    }

    public static EvidenceVector mediaMismatch() {
        List<EvidenceItem> items = new ArrayList<>(baseStrongItems());
        items.add(op(
                EvidencePolarity.OPPOSES_PUBLISH,
                100,
                "MEDIA_SPOT_MISMATCH"));
        return vector(items);
    }

    public static EvidenceVector invalidCoordinates() {
        List<EvidenceItem> items = new ArrayList<>();
        items.addAll(aiStrongContent());
        items.add(legalScore(10));
        items.add(geo(EvidencePolarity.OPPOSES_PUBLISH, 100, "COORDINATES_INVALID"));
        items.add(geo(EvidencePolarity.SUPPORTS_PUBLISH, 100, "SUBMITTER_LEGAL_OK"));
        items.add(op(EvidencePolarity.SUPPORTS_PUBLISH, 100, "AI_EVENT_CORRELATED"));
        return vector(items);
    }

    public static EvidenceVector legalRiskCritical() {
        List<EvidenceItem> items = new ArrayList<>();
        items.addAll(aiStrongContent());
        items.add(legalScore(90));
        items.add(ai(EvidencePolarity.OPPOSES_PUBLISH, 85, "AI_RISK_FIRE_HYDRANT"));
        items.addAll(validLocationAndIntegrity());
        return vector(items);
    }

    public static EvidenceVector staleEvent() {
        List<EvidenceItem> items = new ArrayList<>(baseStrongItems());
        items.add(op(EvidencePolarity.NEUTRAL, 100, "STALE_MODERATION_EVENT"));
        return vector(items);
    }

    public static EvidenceVector missingTrustDeviceH3() {
        // Same as strong normal — no TRUST/DEVICE/H3 evidence types present.
        return strongNormal();
    }

    public static EvidenceVector duplicateIdenticalEvidence() {
        List<EvidenceItem> items = new ArrayList<>(baseStrongItems());
        EvidenceItem duplicate = legalScore(10);
        items.add(duplicate);
        items.add(EvidenceItem.of(
                duplicate.type(),
                duplicate.source(),
                duplicate.polarity(),
                duplicate.strength(),
                duplicate.observedAt(),
                duplicate.reasonCode().orElse(null),
                duplicate.sourceReference().orElse(null)));
        return vector(items);
    }

    public static EvidenceVector conflictingOrderA() {
        return conflictingLegality();
    }

    public static EvidenceVector conflictingOrderB() {
        List<EvidenceItem> items = new ArrayList<>(conflictingLegality().items());
        java.util.Collections.reverse(items);
        return EvidenceVector.of(SPOT_ID, EVAL_ID, T0, items);
    }

    public static EvidenceVector vectorWithNotParking() {
        List<EvidenceItem> items = new ArrayList<>();
        items.add(ai(EvidencePolarity.SUPPORTS_PUBLISH, 55, "AI_STATUS_PASSED"));
        items.add(ai(EvidencePolarity.OPPOSES_PUBLISH, 100, "AI_RISK_NOT_A_PARKING_SPOT"));
        items.add(legalScore(10));
        items.addAll(validLocationAndIntegrity());
        return vector(items);
    }

    private static List<EvidenceItem> baseStrongItems() {
        List<EvidenceItem> items = new ArrayList<>();
        items.addAll(aiStrongContent());
        items.add(legalScore(10));
        items.addAll(validLocationAndIntegrity());
        return items;
    }

    private static List<EvidenceItem> aiStrongContent() {
        return List.of(
                ai(EvidencePolarity.SUPPORTS_PUBLISH, 55, "AI_STATUS_PASSED"),
                ai(EvidencePolarity.SUPPORTS_PUBLISH, 92, "EMPTY_SPACE_CONFIDENCE"),
                ai(EvidencePolarity.SUPPORTS_PUBLISH, 88, "IMAGE_QUALITY_SCORE"),
                ai(EvidencePolarity.NEUTRAL, 90, "AI_CONFIDENCE"));
    }

    private static List<EvidenceItem> validLocationAndIntegrity() {
        return List.of(
                geo(EvidencePolarity.SUPPORTS_PUBLISH, 100, "COORDINATES_VALID"),
                geo(EvidencePolarity.SUPPORTS_PUBLISH, 100, "SUBMITTER_LEGAL_OK"),
                op(EvidencePolarity.SUPPORTS_PUBLISH, 100, "AI_EVENT_CORRELATED"));
    }

    private static EvidenceItem legalScore(int strength) {
        return ai(EvidencePolarity.OPPOSES_PUBLISH, strength, "LEGAL_RISK_SCORE");
    }

    private static EvidenceItem ai(EvidencePolarity polarity, int strength, String reason) {
        return EvidenceItem.of(
                EvidenceType.AI_CONTENT_ANALYSIS,
                EvidenceSource.AI_VALIDATION_SERVICE,
                polarity,
                strength,
                T0,
                ReasonCode.of(reason),
                EVENT_REF);
    }

    private static EvidenceItem geo(EvidencePolarity polarity, int strength, String reason) {
        return EvidenceItem.of(
                EvidenceType.GEOSPATIAL_CONSISTENCY,
                EvidenceSource.PARKING_DOMAIN,
                polarity,
                strength,
                T0,
                ReasonCode.of(reason),
                SPOT_ID.toString());
    }

    private static EvidenceItem op(EvidencePolarity polarity, int strength, String reason) {
        return EvidenceItem.of(
                EvidenceType.OPERATIONAL_PROVENANCE,
                EvidenceSource.SYSTEM,
                polarity,
                strength,
                T0,
                ReasonCode.of(reason),
                EVENT_REF);
    }

    private static EvidenceVector vector(List<EvidenceItem> items) {
        return EvidenceVector.of(SPOT_ID, EVAL_ID, T0, items);
    }
}
