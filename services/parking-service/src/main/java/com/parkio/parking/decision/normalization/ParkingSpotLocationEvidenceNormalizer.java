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

/** Maps parking-spot location and submitter context into geospatial evidence. */
public final class ParkingSpotLocationEvidenceNormalizer {

    private static final Comparator<EvidenceItem> DETERMINISTIC_ORDER =
            Comparator.comparing((EvidenceItem item) -> item.type().name())
                    .thenComparing(item -> item.reasonCode().map(ReasonCode::value).orElse(""));

    public List<EvidenceItem> normalize(ParkingSpotEvidenceContext context, Instant observedAt) {
        List<EvidenceItem> items = new ArrayList<>();
        String sourceRef = context.parkingSpotId().toString();

        if (hasValidCoordinates(context.latitude(), context.longitude())) {
            items.add(EvidenceItem.of(
                    EvidenceType.GEOSPATIAL_CONSISTENCY,
                    EvidenceSource.PARKING_DOMAIN,
                    EvidencePolarity.SUPPORTS_PUBLISH,
                    100,
                    observedAt,
                    ReasonCode.of("COORDINATES_VALID"),
                    sourceRef));
        } else {
            items.add(EvidenceItem.of(
                    EvidenceType.GEOSPATIAL_CONSISTENCY,
                    EvidenceSource.PARKING_DOMAIN,
                    EvidencePolarity.OPPOSES_PUBLISH,
                    100,
                    observedAt,
                    ReasonCode.of("COORDINATES_INVALID"),
                    sourceRef));
        }

        if (context.manualLocationEdited()) {
            items.add(EvidenceItem.of(
                    EvidenceType.GEOSPATIAL_CONSISTENCY,
                    EvidenceSource.PARKING_DOMAIN,
                    EvidencePolarity.NEUTRAL,
                    50,
                    observedAt,
                    ReasonCode.of("MANUAL_LOCATION_EDITED"),
                    sourceRef));
        }

        items.add(mapLegalStatus(context.legalStatusName(), observedAt, sourceRef));
        items.sort(DETERMINISTIC_ORDER);
        return List.copyOf(items);
    }

    private static EvidenceItem mapLegalStatus(String legalStatus, Instant observedAt, String sourceRef) {
        return switch (legalStatus) {
            case "ILLEGAL_OR_RISKY" -> EvidenceItem.of(
                    EvidenceType.GEOSPATIAL_CONSISTENCY,
                    EvidenceSource.PARKING_DOMAIN,
                    EvidencePolarity.OPPOSES_PUBLISH,
                    80,
                    observedAt,
                    ReasonCode.of("SUBMITTER_LEGAL_RISK"),
                    sourceRef);
            case "UNCERTAIN" -> EvidenceItem.of(
                    EvidenceType.GEOSPATIAL_CONSISTENCY,
                    EvidenceSource.PARKING_DOMAIN,
                    EvidencePolarity.NEUTRAL,
                    50,
                    observedAt,
                    ReasonCode.of("SUBMITTER_LEGAL_UNCERTAIN"),
                    sourceRef);
            default -> EvidenceItem.of(
                    EvidenceType.GEOSPATIAL_CONSISTENCY,
                    EvidenceSource.PARKING_DOMAIN,
                    EvidencePolarity.SUPPORTS_PUBLISH,
                    30,
                    observedAt,
                    ReasonCode.of("SUBMITTER_LEGAL_OK"),
                    sourceRef);
        };
    }

    private static boolean hasValidCoordinates(double latitude, double longitude) {
        return latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0;
    }
}
