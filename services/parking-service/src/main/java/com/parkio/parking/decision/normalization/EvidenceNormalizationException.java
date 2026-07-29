package com.parkio.parking.decision.normalization;

/**
 * Fatal evidence normalization failure. Must not be mapped to a publication disposition.
 */
public final class EvidenceNormalizationException extends RuntimeException {

    public EvidenceNormalizationException(String message) {
        super(message);
    }

    public EvidenceNormalizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
