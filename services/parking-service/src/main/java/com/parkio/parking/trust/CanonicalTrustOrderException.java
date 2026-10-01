package com.parkio.parking.trust;

/** The evidence is older than the snapshot it was applied to. */
public final class CanonicalTrustOrderException extends IllegalArgumentException {

    public CanonicalTrustOrderException() {
        super("trust evaluations must be replayed in canonical order");
    }
}
