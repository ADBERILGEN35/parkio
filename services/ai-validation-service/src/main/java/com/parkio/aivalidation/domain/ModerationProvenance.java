package com.parkio.aivalidation.domain;

import java.util.Objects;

/**
 * Traceability metadata for an advisory validation result: exactly which provider,
 * model/prompt/policy/threshold versions, canonical image identity, and request
 * identity produced it, plus the {@link DecisionSource}.
 *
 * <p>Every field is nullable so legacy rows (pre-provenance) and the placeholder
 * heuristic remain representable. The <em>version tuple</em>
 * ({@link #modelId}/{@link #modelVersion}/{@link #promptVersion}/{@link #policyVersion}/
 * {@link #thresholdVersion}) drives cross-version reuse gating: a result may only be
 * reused when its tuple equals the current one, so a decision made under an old
 * prompt/policy/threshold/model is never silently reused.
 *
 * <p>Hashes are stored in full for correlation but must only be logged via
 * {@link #safeHashPrefix()} / {@link #safeRequestIdentityPrefix()}.
 */
public record ModerationProvenance(
        DecisionSource decisionSource,
        String provider,
        String modelId,
        String modelVersion,
        String promptVersion,
        String policyVersion,
        String thresholdVersion,
        String canonicalImageHash,
        Double rawConfidence,
        String requestIdentity,
        String requestIdentityVersion) {

    private static final ModerationProvenance NONE =
            new ModerationProvenance(null, null, null, null, null, null, null, null, null, null, null);

    /** Empty provenance for legacy rows or callers that supply none. */
    public static ModerationProvenance none() {
        return NONE;
    }

    /** Placeholder heuristic path (no provider, no model verdict). */
    public static ModerationProvenance heuristic() {
        return new ModerationProvenance(DecisionSource.HEURISTIC, null, null, null, null, null, null,
                null, null, null, null);
    }

    /** True when the model/prompt/policy/threshold version tuple is identical. */
    public boolean sameVersionTuple(ModerationProvenance other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(modelId, other.modelId)
                && Objects.equals(modelVersion, other.modelVersion)
                && Objects.equals(promptVersion, other.promptVersion)
                && Objects.equals(policyVersion, other.policyVersion)
                && Objects.equals(thresholdVersion, other.thresholdVersion);
    }

    /** True when the version tuple is fully populated (safe to gate reuse on). */
    public boolean hasCompleteVersionTuple() {
        return modelId != null && modelVersion != null && promptVersion != null
                && policyVersion != null && thresholdVersion != null;
    }

    /** Short, non-reversible prefix of the canonical image hash for logs. */
    public String safeHashPrefix() {
        return prefix(canonicalImageHash);
    }

    /** Short, non-reversible prefix of the request identity for logs. */
    public String safeRequestIdentityPrefix() {
        return prefix(requestIdentity);
    }

    private static String prefix(String hash) {
        if (hash == null || hash.isBlank()) {
            return "none";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    /** Returns a copy carrying the given decision source. */
    public ModerationProvenance withDecisionSource(DecisionSource source) {
        return new ModerationProvenance(source, provider, modelId, modelVersion, promptVersion,
                policyVersion, thresholdVersion, canonicalImageHash, rawConfidence,
                requestIdentity, requestIdentityVersion);
    }
}
