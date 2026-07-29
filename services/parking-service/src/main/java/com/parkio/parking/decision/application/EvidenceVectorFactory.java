package com.parkio.parking.decision.application;

import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.normalization.EvidenceNormalizationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Assembles a canonical {@link EvidenceVector} from pre-normalized evidence items.
 * Does not score, decide, or persist.
 */
public final class EvidenceVectorFactory {

    public EvidenceVector assemble(
            UUID parkingSpotId,
            UUID evaluationId,
            Instant collectedAt,
            List<EvidenceItem> items) {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(collectedAt, "collectedAt");
        Objects.requireNonNull(items, "items");

        List<EvidenceItem> deduped = dedupePreservingOrder(items);
        return EvidenceVector.of(parkingSpotId, evaluationId, collectedAt, deduped);
    }

    /**
     * Repeated assembly with identical items yields an equal vector. Semantically distinct
     * observations remain distinct even when strengths match.
     */
    private static List<EvidenceItem> dedupePreservingOrder(List<EvidenceItem> items) {
        Set<EvidenceItem> seen = new LinkedHashSet<>();
        List<EvidenceItem> deduped = new ArrayList<>(items.size());
        for (EvidenceItem item : items) {
            if (item == null) {
                throw new EvidenceNormalizationException("evidence items must not contain null");
            }
            if (seen.add(item)) {
                deduped.add(item);
            }
        }
        return deduped;
    }
}
