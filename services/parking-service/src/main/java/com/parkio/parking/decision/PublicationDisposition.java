package com.parkio.parking.decision;

/**
 * Future-facing publication disposition produced by the Decision Engine.
 *
 * <p>Related to, but <strong>not identical</strong> with, {@code ParkingSpotStatus}.
 * WP-05.2 defines vocabulary only — no runtime mapping is activated.
 *
 * <ul>
 *   <li>{@link #FULL_PUBLISH} — eligible for normal visibility under active policy</li>
 *   <li>{@link #LIMITED_PUBLISH} — restricted / adaptive exposure only (not implemented yet)</li>
 *   <li>{@link #HOLD} — not published while additional evidence or async validation is awaited</li>
 *   <li>{@link #SHADOW} — not publicly visible; retained for security / fraud observation (not ordinary rejection)</li>
 *   <li>{@link #EXPIRED} — no longer publishable; availability claim stale or window ended</li>
 *   <li>{@link #REJECTED} — final non-publication under evaluated policy</li>
 * </ul>
 */
public enum PublicationDisposition {
    FULL_PUBLISH,
    LIMITED_PUBLISH,
    HOLD,
    SHADOW,
    EXPIRED,
    REJECTED
}