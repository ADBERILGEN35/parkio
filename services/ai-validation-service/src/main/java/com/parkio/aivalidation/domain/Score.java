package com.parkio.aivalidation.domain;

import com.parkio.aivalidation.domain.exception.AiValidationErrorCode;
import com.parkio.aivalidation.domain.exception.AiValidationException;

/** Domain invariant for advisory scores: they must lie within 0-100 (ai-context/02). */
public final class Score {

    public static final int MIN = 0;
    public static final int MAX = 100;

    private Score() {
    }

    /** Returns {@code value} if it is in range, otherwise throws {@link AiValidationException}. */
    public static int require(String name, int value) {
        if (value < MIN || value > MAX) {
            throw new AiValidationException(AiValidationErrorCode.INVALID_SCORE,
                    name + " must be between " + MIN + " and " + MAX + " (was " + value + ").");
        }
        return value;
    }

    /** Clamps a computed value into 0-100 (used by the placeholder validator). */
    public static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }
}
