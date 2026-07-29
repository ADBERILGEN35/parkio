package com.parkio.parking.decision.port;

import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidenceType;
import java.util.List;
import java.util.UUID;

/**
 * Supplies normalized evidence items for one category / provider.
 *
 * <p>Implementations live outside the core decision domain (infrastructure adapters).
 * No default implementation in WP-05.2.
 */
public interface EvidenceProvider {

    /** Evidence category this provider contributes. */
    EvidenceType evidenceType();

    /**
     * Returns zero or more items for the given evaluation. MUST NOT return null;
     * MUST NOT embed Spring/Kafka/JPA types.
     */
    List<EvidenceItem> provide(UUID parkingSpotId, UUID evaluationId);
}