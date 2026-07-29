package com.parkio.parking.decision.port;

import com.parkio.parking.decision.evidence.EvidenceItem;
import java.util.Optional;
import java.util.UUID;

/**
 * Loads recorded human moderator decisions as weighted evidence overrides
 * (ADR-WP05 {@code ModeratorOverridePort}).
 *
 * <p>Overrides are evidence inputs, not silent status writers. No implementation in WP-05.2.
 */
public interface ModeratorOverridePort {

    Optional<EvidenceItem> loadOverride(UUID parkingSpotId);
}