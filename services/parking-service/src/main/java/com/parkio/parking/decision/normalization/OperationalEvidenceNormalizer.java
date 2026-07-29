package com.parkio.parking.decision.normalization;

import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidencePolarity;
import com.parkio.parking.decision.evidence.EvidenceSource;
import com.parkio.parking.decision.evidence.EvidenceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Operational and provenance evidence from AI event correlation and ordering metadata. */
public final class OperationalEvidenceNormalizer {

    private static final Comparator<EvidenceItem> DETERMINISTIC_ORDER =
            Comparator.comparing((EvidenceItem item) -> item.reasonCode().map(ReasonCode::value).orElse(""));

    public List<EvidenceItem> normalize(
            AiValidationEvidenceInput input, Optional<ParkingSpotEvidenceContext> spotContext) {
        Instant observedAt = input.occurredAt();
        String eventRef = input.eventId().toString();
        List<EvidenceItem> items = new ArrayList<>();

        items.add(EvidenceItem.of(
                EvidenceType.OPERATIONAL_PROVENANCE,
                EvidenceSource.SYSTEM,
                EvidencePolarity.SUPPORTS_PUBLISH,
                100,
                observedAt,
                ReasonCode.of("AI_EVENT_CORRELATED"),
                eventRef));

        spotContext.ifPresent(context -> {
            if (!context.mediaId().equals(input.mediaId())) {
                items.add(EvidenceItem.of(
                        EvidenceType.OPERATIONAL_PROVENANCE,
                        EvidenceSource.SYSTEM,
                        EvidencePolarity.OPPOSES_PUBLISH,
                        100,
                        observedAt,
                        ReasonCode.of("MEDIA_SPOT_MISMATCH"),
                        eventRef));
            }
            if (context.isStaleModerationEvent(observedAt)) {
                items.add(EvidenceItem.of(
                        EvidenceType.OPERATIONAL_PROVENANCE,
                        EvidenceSource.SYSTEM,
                        EvidencePolarity.NEUTRAL,
                        100,
                        observedAt,
                        ReasonCode.of("STALE_MODERATION_EVENT"),
                        eventRef));
            }
        });

        items.sort(DETERMINISTIC_ORDER);
        return List.copyOf(items);
    }
}
