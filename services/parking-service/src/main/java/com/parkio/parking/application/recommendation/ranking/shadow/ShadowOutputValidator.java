package com.parkio.parking.application.recommendation.ranking.shadow;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates challenger output against the expected alias set from the request.
 * Invalid confidence / categories / alias cover → invalid.
 */
public final class ShadowOutputValidator {

    private ShadowOutputValidator() {}

    public static boolean isValid(ShadowRankingRequest request, ShadowRankingOutput output) {
        Objects.requireNonNull(request, "request");
        if (output == null) {
            return false;
        }
        if (output.schemaVersion() == null || output.schemaVersion().isBlank()) {
            return false;
        }
        if (output.confidence() == null) {
            return false;
        }
        List<String> ordered = output.orderedCandidateAliases();
        if (ordered == null) {
            return false;
        }
        Set<String> expected = expectedAliases(request);
        if (ordered.size() != expected.size()) {
            return false;
        }
        Set<String> seen = new HashSet<>(ordered.size());
        for (String alias : ordered) {
            if (alias == null || alias.isBlank()) {
                return false;
            }
            if (!expected.contains(alias)) {
                return false;
            }
            if (!seen.add(alias)) {
                return false;
            }
        }
        if (seen.size() != expected.size()) {
            return false;
        }
        Map<String, List<ShadowReasonCategory>> categories = output.reasonCategories();
        if (categories == null) {
            return false;
        }
        for (Map.Entry<String, List<ShadowReasonCategory>> entry : categories.entrySet()) {
            if (entry.getKey() == null || !expected.contains(entry.getKey())) {
                return false;
            }
            List<ShadowReasonCategory> values = entry.getValue();
            if (values == null) {
                return false;
            }
            for (ShadowReasonCategory category : values) {
                if (category == null) {
                    return false;
                }
            }
        }
        return true;
    }

    static Set<String> expectedAliases(ShadowRankingRequest request) {
        Set<String> aliases = new HashSet<>();
        for (ShadowCandidateFeatures features : request.candidates()) {
            aliases.add(features.alias());
        }
        return aliases;
    }
}
