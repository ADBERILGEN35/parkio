package com.parkio.parking.decision.calibration;

import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidenceType;
import com.parkio.parking.decision.evidence.EvidenceVector;
import java.util.Objects;

/** Classifies current v1 evidence availability without inventing future categories. */
public final class EvidenceAvailabilityClassifier {

    private EvidenceAvailabilityClassifier() {}

    public static EvidenceAvailabilityProfile from(EvidenceVector vector) {
        Objects.requireNonNull(vector, "vector");
        if (vector.isEmpty()) {
            return EvidenceAvailabilityProfile.UNKNOWN;
        }
        boolean ai = false;
        boolean operational = false;
        boolean location = false;
        for (EvidenceItem item : vector.items()) {
            EvidenceType type = item.type();
            if (type == EvidenceType.AI_CONTENT_ANALYSIS) {
                ai = true;
            } else if (type == EvidenceType.OPERATIONAL_PROVENANCE) {
                operational = true;
            } else if (type == EvidenceType.GEOSPATIAL_CONSISTENCY) {
                location = true;
            }
        }
        if (ai && operational && location) {
            return EvidenceAvailabilityProfile.COMPLETE_CURRENT_V1;
        }
        if (ai && operational && !location) {
            return EvidenceAvailabilityProfile.AI_PLUS_OPERATIONAL;
        }
        if (ai && location && !operational) {
            return EvidenceAvailabilityProfile.AI_PLUS_LOCATION;
        }
        if (ai && !operational && !location) {
            return EvidenceAvailabilityProfile.AI_ONLY;
        }
        if (!ai && !operational && !location) {
            return EvidenceAvailabilityProfile.UNKNOWN;
        }
        return EvidenceAvailabilityProfile.PARTIAL;
    }
}
