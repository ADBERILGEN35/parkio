package com.parkio.aivalidation.infrastructure.classifier;

import com.parkio.aivalidation.domain.ContentRiskClassifier;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Fail-closed content classifier used until a real vision provider is wired.
 *
 * <p>Default without an override: {@link Verdict#UNCERTAIN} so the AI gate forces
 * {@code WARNING} to parking {@code PENDING_REVIEW} (not publicly discoverable).
 *
 * <p>Unit / fixture overrides (process-local):
 * <ul>
 *   <li>System property {@code parkio.ai.classifier.override.&lt;mediaId&gt;} =
 *       {@code LIKELY_PARKING} / {@code UNCERTAIN} / {@code NOT_A_PARKING_SPOT}</li>
 *   <li>{@link #putOverride(UUID, Verdict)} for in-process test fixtures</li>
 * </ul>
 */
@Component
public class HeuristicContentRiskClassifier implements ContentRiskClassifier {

    private static final String SYSTEM_PROPERTY_PREFIX = "parkio.ai.classifier.override.";

    private static final Map<String, Verdict> OVERRIDES = new ConcurrentHashMap<>();

    /** Registers a test/fixture override for {@code mediaId}. */
    public static void putOverride(UUID mediaId, Verdict verdict) {
        OVERRIDES.put(mediaId.toString(), verdict);
    }

    /** Clears a single override (tests). */
    public static void clearOverride(UUID mediaId) {
        OVERRIDES.remove(mediaId.toString());
    }

    /** Clears all in-memory overrides (tests). */
    public static void clearAllOverrides() {
        OVERRIDES.clear();
    }

    @Override
    public Verdict classify(UUID mediaId) {
        String key = mediaId.toString();
        Verdict fromMap = OVERRIDES.get(key);
        if (fromMap != null) {
            return fromMap;
        }
        String fromProperty = System.getProperty(SYSTEM_PROPERTY_PREFIX + key);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Verdict.valueOf(fromProperty.trim().toUpperCase());
        }
        // Fail-closed: no media bytes / no vision model -> uncertain, not public.
        return Verdict.UNCERTAIN;
    }
}