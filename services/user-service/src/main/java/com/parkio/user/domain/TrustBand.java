package com.parkio.user.domain;

/**
 * Coarse trust tier derived from the trust score (0–100). This is a PROJECTION
 * band: user-service stores it as a read copy maintained from gamification
 * events and never computes the underlying score itself (ai-context/02, 03).
 * New profiles start {@link #HIGH_TRUST} with a score of 100.
 */
public enum TrustBand {
    UNTRUSTED,
    LOW_TRUST,
    MEDIUM_TRUST,
    HIGH_TRUST;

    /**
     * Maps a score to its band. Used only to keep the stored band consistent with
     * a projected score; the thresholds are presentation of an externally-owned
     * score, not a scoring computation.
     */
    public static TrustBand forScore(int score) {
        if (score >= 75) {
            return HIGH_TRUST;
        }
        if (score >= 50) {
            return MEDIUM_TRUST;
        }
        if (score >= 25) {
            return LOW_TRUST;
        }
        return UNTRUSTED;
    }
}
