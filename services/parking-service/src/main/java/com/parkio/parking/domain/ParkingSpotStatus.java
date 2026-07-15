package com.parkio.parking.domain;

/**
 * Lifecycle of a parking spot.
 *
 * <ul>
 *   <li>{@code PENDING_VALIDATION} - created, waiting for AI publication gate.</li>
 *   <li>{@code PENDING_REVIEW} - AI uncertain / warning; not publicly discoverable.</li>
 *   <li>{@code ACTIVE} - AI validation passed; within its validity window.</li>
 *   <li>{@code VERIFIED} - at least one user confirmed it as available.</li>
 *   <li>{@code SUSPICIOUS} - one negative signal (a filled report / low confidence).</li>
 *   <li>{@code FILLED} - taken (claimed) or confirmed full by reports (terminal).</li>
 *   <li>{@code EXPIRED} - validity window elapsed (terminal).</li>
 *   <li>{@code REJECTED} - found illegal/risky or rejected by AI (terminal).</li>
 * </ul>
 */
public enum ParkingSpotStatus {
    PENDING_VALIDATION,
    PENDING_REVIEW,
    ACTIVE,
    VERIFIED,
    SUSPICIOUS,
    FILLED,
    EXPIRED,
    REJECTED
}