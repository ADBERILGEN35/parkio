package com.parkio.parking.application.recommendation.ranking.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hard privacy allowlist for durable ranking evaluation payloads (WP-SPA-14B).
 * Fail closed: unknown top-level keys and forbidden nested field names reject persistence.
 */
public final class RankingEvaluationPrivacyGuard {

    private static final Set<String> ALLOWED_FEATURE_KEYS = Set.of(
            "candidateOrdinal",
            "alias",
            "channel",
            "distanceBucket",
            "distanceNormalized",
            "occupancyFreshnessKind",
            "availabilityBucket",
            "availabilityRatioBucket",
            "capacityBucket",
            "inventoryConfidenceBucket",
            "isFavourite",
            "reasonCodes",
            "deterministicScoreBucket",
            "deterministicPosition");

    private static final Set<String> FORBIDDEN_FIELD_NAMES = Set.of(
            "userid",
            "user_id",
            "latitude",
            "longitude",
            "lat",
            "lng",
            "destination",
            "label",
            "address",
            "addresstext",
            "facilityid",
            "facility_id",
            "spotid",
            "spot_id",
            "sessionid",
            "session_id",
            "targetid",
            "target_id",
            "refid",
            "ref_id",
            "externalid",
            "external_id",
            "providerplaceid",
            "provider_place_id",
            "searchquery",
            "search_query",
            "email",
            "phone",
            "title",
            "displayname",
            "display_name");

    private RankingEvaluationPrivacyGuard() {}

    public static void assertFeaturesJsonAllowed(ObjectMapper mapper, String featuresJson) {
        if (featuresJson == null || featuresJson.isBlank()) {
            throw new IllegalArgumentException("featuresJson required");
        }
        try {
            JsonNode root = mapper.readTree(featuresJson);
            if (!root.isArray()) {
                throw new IllegalArgumentException("featuresJson must be a JSON array");
            }
            for (JsonNode node : root) {
                assertObjectAllowlisted(node, ALLOWED_FEATURE_KEYS);
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("featuresJson invalid", ex);
        }
    }

    public static void assertNoForbiddenFields(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            walkForbidden(mapper.readTree(json));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("json invalid", ex);
        }
    }

    public static void assertOrdinalListJson(ObjectMapper mapper, String json, int candidateCount) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("ordinal list required");
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isArray()) {
                throw new IllegalArgumentException("ordinal list must be array");
            }
            boolean[] seen = new boolean[Math.max(candidateCount, 0)];
            for (JsonNode node : root) {
                if (!node.isInt() && !node.isLong()) {
                    throw new IllegalArgumentException("ordinal must be integer");
                }
                int ordinal = node.asInt();
                if (ordinal < 0 || ordinal >= candidateCount) {
                    throw new IllegalArgumentException("ordinal out of bounds");
                }
                if (seen[ordinal]) {
                    throw new IllegalArgumentException("duplicate ordinal");
                }
                seen[ordinal] = true;
            }
            if (root.size() != candidateCount) {
                throw new IllegalArgumentException("ordinal list size mismatch");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("ordinal list invalid", ex);
        }
    }

    private static void assertObjectAllowlisted(JsonNode node, Set<String> allowedKeys) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("feature entry must be object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (!allowedKeys.contains(key)) {
                throw new IllegalArgumentException("feature key not allowlisted: " + key);
            }
            assertForbiddenName(key);
            walkForbidden(entry.getValue());
        }
    }

    private static void walkForbidden(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                assertForbiddenName(entry.getKey());
                walkForbidden(entry.getValue());
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                walkForbidden(child);
            }
        }
    }

    private static void assertForbiddenName(String key) {
        if (key == null) {
            return;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "");
        if (FORBIDDEN_FIELD_NAMES.contains(normalized)) {
            throw new IllegalArgumentException("forbidden field: " + key);
        }
    }
}
