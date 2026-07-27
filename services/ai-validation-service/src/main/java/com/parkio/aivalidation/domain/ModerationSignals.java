package com.parkio.aivalidation.domain;

import java.util.List;

/**
 * Structured moderation signals persisted alongside the gate verdict. Legality and
 * image relevance are kept separate so legal uncertainty does not imply rejection.
 */
public record ModerationSignals(
        String sceneType,
        boolean roadContextPresent,
        boolean parkingContextPresent,
        boolean vehicleSizedOpenSpacePresent,
        boolean clearlyIrrelevantContent,
        boolean imageUsable,
        boolean possibleSafetyOrLegalityConcern,
        double parkingContextConfidence,
        double vehicleSizedOpenSpaceConfidence,
        double clearlyIrrelevantConfidence,
        double unusableImageConfidence,
        List<String> reasonCodes) {

    public ModerationSignals {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }
}
