package com.parkio.aivalidation.domain;

import java.util.UUID;

/**
 * Classifies whether media depicts a parking spot. Used by the placeholder validator
 * to fail-closed (uncertain / not parking) until a real vision adapter is wired.
 *
 * <p>Production should supply a vision-backed implementation. The default heuristic
 * returns {@link Verdict#UNCERTAIN} so parking-service keeps spots out of public
 * discovery ({@code PENDING_REVIEW}) rather than publishing them.
 */
public interface ContentRiskClassifier {

    enum Verdict {
        LIKELY_PARKING,
        UNCERTAIN,
        NOT_A_PARKING_SPOT
    }

    Verdict classify(UUID mediaId);

    /**
     * Detailed classification including whether the verdict is semantic or
     * infrastructure fail-closed. Default wraps {@link #classify(UUID)} as SEMANTIC.
     */
    default ContentClassification classifyDetailed(UUID mediaId) {
        return ContentClassification.semantic(classify(mediaId), null);
    }
}
