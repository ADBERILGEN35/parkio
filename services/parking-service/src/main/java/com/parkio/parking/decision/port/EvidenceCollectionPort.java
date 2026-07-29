package com.parkio.parking.decision.port;

import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.normalization.EvidenceCollectionRequest;

/**
 * Assembles a canonical {@link EvidenceVector} for one Decision evaluation from
 * caller-supplied, already-loaded inputs.
 *
 * <p>Separates evidence acquisition from decision policy. WP-05.3 implementation:
 * {@link com.parkio.parking.decision.application.EvidenceCollectionService}.
 */
public interface EvidenceCollectionPort {

    EvidenceVector collect(EvidenceCollectionRequest request);
}
