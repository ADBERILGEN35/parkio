package com.parkio.parking.domain;

/**
 * Lifecycle of a parking spot.
 *
 * <ul>
 *   <li>{@code ACTIVE} — freshly created, within its validity window.</li>
 *   <li>{@code VERIFIED} — at least one user confirmed it as available.</li>
 *   <li>{@code SUSPICIOUS} — one negative signal (a filled report / low confidence).</li>
 *   <li>{@code FILLED} — taken (claimed) or confirmed full by reports (terminal).</li>
 *   <li>{@code EXPIRED} — validity window elapsed (terminal).</li>
 *   <li>{@code REJECTED} — found illegal/risky (terminal).</li>
 * </ul>
 */
public enum ParkingSpotStatus {
    ACTIVE,
    VERIFIED,
    SUSPICIOUS,
    FILLED,
    EXPIRED,
    REJECTED
}
