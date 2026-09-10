package com.parkio.parking.application.recommendation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Structured reason attached to a candidate or response. Clients own localization
 * via {@link #messageKey()}; the backend does not author final Turkish copy.
 */
public record RecommendationReason(
        RecommendationReasonCode code,
        Map<String, Object> parameters,
        String messageKey) {

    public RecommendationReason {
        Objects.requireNonNull(code, "code");
        parameters = parameters == null || parameters.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        messageKey = messageKey == null || messageKey.isBlank()
                ? "recommendation.reason." + code.name()
                : messageKey.trim();
    }

    public static RecommendationReason of(RecommendationReasonCode code) {
        return new RecommendationReason(code, Map.of(), null);
    }

    public static RecommendationReason of(RecommendationReasonCode code, Map<String, Object> parameters) {
        return new RecommendationReason(code, parameters, null);
    }
}
