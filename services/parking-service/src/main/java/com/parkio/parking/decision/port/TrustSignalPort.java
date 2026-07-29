package com.parkio.parking.decision.port;

import com.parkio.parking.decision.score.TrustScore;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads actor trust for Decision inputs (ADR-WP05 {@code TrustSignalPort}).
 *
 * <p>Returns empty when trust is unknown — never a synthetic zero. No implementation in WP-05.2.
 */
public interface TrustSignalPort {

    Optional<TrustScore> loadTrust(UUID ownerUserId);
}