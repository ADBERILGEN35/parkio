package com.parkio.aivalidation.infrastructure.vision;

import com.parkio.aivalidation.application.port.AiValidationResultRepository;
import com.parkio.aivalidation.domain.AiRiskType;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.AiValidationStatus;
import com.parkio.aivalidation.domain.ContentClassification;
import com.parkio.aivalidation.domain.ContentRiskClassifier;
import com.parkio.aivalidation.domain.DecisionSource;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
import com.parkio.aivalidation.domain.ModerationProvenance;
import com.parkio.aivalidation.infrastructure.config.VisionProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real vision-backed {@link ContentRiskClassifier}.
 *
 * <p><b>Single-flight:</b> concurrent classify calls for the same mediaId share one
 * in-JVM future. This is an optimization only — multi-instance deployments still rely
 * on persisted conclusive / semantic-UNCERTAIN results and inbox idempotency as the
 * correctness boundary.
 *
 * <p><b>Reuse:</b> conclusive PASSED/FAILED results are always reused. Genuine SEMANTIC
 * UNCERTAIN is reused within {@code semanticUncertainReuseTtl}. Infrastructure
 * fail-closed results are never treated as semantic cache hits.
 */
public class VisionContentRiskClassifier implements ContentRiskClassifier {

    private static final Logger log = LoggerFactory.getLogger(VisionContentRiskClassifier.class);

    /** Algorithm version for {@code requestIdentity}; bump if the identity recipe changes. */
    static final String REQUEST_IDENTITY_VERSION = "v1";

    private final MediaContentFetcher mediaContentFetcher;
    private final VisionProviderClient providerClient;
    private final AiValidationResultRepository results;
    private final VisionProperties properties;
    private final VisionMetrics metrics;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, CompletableFuture<ContentClassification>> inFlight =
            new ConcurrentHashMap<>();

    public VisionContentRiskClassifier(MediaContentFetcher mediaContentFetcher,
                                       VisionProviderClient providerClient,
                                       AiValidationResultRepository results,
                                       VisionProperties properties,
                                       VisionMetrics metrics,
                                       Clock clock) {
        this.mediaContentFetcher = mediaContentFetcher;
        this.providerClient = providerClient;
        this.results = results;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Verdict classify(UUID mediaId) {
        return classifyDetailed(mediaId).verdict();
    }

    @Override
    public ContentClassification classifyDetailed(UUID mediaId) {
        Instant start = clock.instant();
        Optional<ContentClassification> reused = reusableClassification(mediaId);
        if (reused.isPresent()) {
            metrics.recordOutcome("REUSED", Duration.between(start, clock.instant()));
            log.info("Vision validation reused persisted classification {} for media {}",
                    reused.get().verdict(), mediaId);
            return reused.get();
        }

        CompletableFuture<ContentClassification> created = new CompletableFuture<>();
        CompletableFuture<ContentClassification> existing = inFlight.putIfAbsent(mediaId, created);
        if (existing != null) {
            return existing.join();
        }
        try {
            // Re-check after acquiring the guard (another thread may have persisted).
            Optional<ContentClassification> again = reusableClassification(mediaId);
            if (again.isPresent()) {
                created.complete(again.get());
                return again.get();
            }
            ContentClassification result = doClassify(mediaId, start);
            created.complete(result);
            return result;
        } catch (RuntimeException ex) {
            created.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(mediaId, created);
        }
    }

    private ContentClassification doClassify(UUID mediaId, Instant start) {
        log.info("Vision validation started for media {} (provider={}, model={})",
                mediaId, providerClient.providerId(), providerClient.modelId());
        try {
            MediaContentFetcher.MediaContent content = mediaContentFetcher.fetch(mediaId);
            VisionProviderClient.VisionAnalysis analysis =
                    providerClient.analyze(content.bytes(), content.contentType(), content.claimedRegion());
            if (analysis.usage() != null) {
                metrics.recordUsage(analysis.usage());
            }
            String canonicalImageHash = sha256Hex(content.bytes());
            String requestIdentity = computeRequestIdentity(canonicalImageHash, content.claimedRegion());
            ModerationProvenance provenance = new ModerationProvenance(
                    DecisionSource.AUTOMATED, providerClient.providerId(), providerClient.modelId(),
                    providerClient.modelVersion(), properties.getPromptVersion(),
                    properties.getPolicyVersion(), properties.getThresholdVersion(),
                    canonicalImageHash, analysis.confidence(), requestIdentity, REQUEST_IDENTITY_VERSION);
            ContentClassification classification = applyConfidencePolicy(analysis).withProvenance(provenance);
            Duration duration = Duration.between(start, clock.instant());
            metrics.recordOutcome(classification.verdict().name(), duration);
            metrics.markSuccess();
            log.info("Vision validation completed for media {}: verdict={} outcome={} "
                            + "confidence={} reasonCode={} source=AUTOMATED model={} modelVersion={} "
                            + "promptVersion={} policyVersion={} thresholdVersion={} hashPrefix={} "
                            + "reqIdPrefix={} durationMs={}",
                    mediaId, classification.verdict(), classification.outcomeKind(),
                    String.format(Locale.ROOT, "%.2f", analysis.confidence()),
                    analysis.reasonCode(), provenance.modelId(), provenance.modelVersion(),
                    provenance.promptVersion(), provenance.policyVersion(), provenance.thresholdVersion(),
                    provenance.safeHashPrefix(), provenance.safeRequestIdentityPrefix(), duration.toMillis());
            return classification;
        } catch (MediaContentException ex) {
            return failClosed(mediaId, start,
                    "media_" + ex.reason().name().toLowerCase(Locale.ROOT), ex);
        } catch (VisionProviderException ex) {
            metrics.recordProviderError(ex.category().name().toLowerCase(Locale.ROOT));
            return failClosed(mediaId, start,
                    ex.category().name().toLowerCase(Locale.ROOT), ex);
        } catch (RuntimeException ex) {
            return failClosed(mediaId, start, "unexpected", ex);
        }
    }

    /**
     * Reject reason codes that may map to {@code NOT_A_PARKING_SPOT}. Soft/ambiguous
     * codes are forced to {@code UNCERTAIN} even if the model returned a hard reject.
     */
    /** High-confidence irrelevant/unusable only; ambiguity routes to manual review. */
    static final Set<String> CONCRETE_REJECT_REASON_CODES = Set.of(
            "UNRELATED_SUBJECT",
            "SCREENSHOT_OR_SYNTHETIC",
            "TOO_DARK_OR_BLURRY");

    static final Set<String> FORCE_UNCERTAIN_REASON_CODES = Set.of(
            "NO_PLAUSIBLE_SPACE",
            "TARGET_PHYSICALLY_BLOCKED",
            "CLEARLY_RESTRICTED_AREA",
            "NEARBY_BARRIER_NOT_BLOCKING_TARGET",
            "LEGALITY_UNCERTAIN",
            "POSSIBLE_SPACE_UNCERTAIN_WIDTH",
            "POSSIBLE_SPACE_UNCLEAR_ACCESS",
            "WHOLE_IMAGE_NO_REGION");

    private ContentClassification applyConfidencePolicy(VisionProviderClient.VisionAnalysis analysis) {
        String reasonCode = analysis.reasonCode() == null ? "OTHER" : analysis.reasonCode();
        ModerationDecisionPolicy.Result policyResult =
                ModerationDecisionPolicy.evaluate(analysis, properties);
        Verdict verdict = policyResult.verdict();
        metrics.recordModerationDecision(policyResult.decision().name(), reasonCode);
        return ContentClassification.semantic(
                verdict,
                reasonCode,
                analysis.claimedRegionAssessment(),
                analysis.vehicleFitEstimate(),
                analysis.obstructionAssessment(),
                analysis.legalityAccessAssessment(),
                policyResult.decision(),
                policyResult.signals(),
                policyResult.rejectionReasonCode());
    }

    private Optional<ContentClassification> reusableClassification(UUID mediaId) {
        return results.findByMediaId(mediaId).stream()
                .max(Comparator.comparing(AiValidationResult::createdAt))
                .flatMap(result -> mapReusable(result, clock.instant()));
    }

    /**
     * Decides whether a persisted result may be reused. A candidate verdict is derived
     * from the persisted status, then <b>gated by the version tuple</b>: a result whose
     * model/prompt/policy/threshold version differs from the current configuration (or a
     * legacy result with an incomplete tuple) is never reused — the classifier re-runs
     * under the current version. This is the "no cross-version reuse" guarantee.
     */
    private Optional<ContentClassification> mapReusable(AiValidationResult result, Instant now) {
        Optional<Verdict> reuseVerdict = reuseVerdictOf(result, now);
        if (reuseVerdict.isEmpty()) {
            return Optional.empty();
        }
        ModerationProvenance persisted = result.provenance();
        if (!currentVersionMatches(persisted)) {
            metrics.recordVersionMismatchRerun();
            log.info("Skipping reuse for media {}: persisted version tuple differs from current "
                            + "(persisted model={}/{} prompt={} policy={} threshold={}); re-running",
                    result.mediaId(), persisted.modelId(), persisted.modelVersion(),
                    persisted.promptVersion(), persisted.policyVersion(), persisted.thresholdVersion());
            return Optional.empty();
        }
        metrics.recordReuse();
        ModerationProvenance reused = persisted.withDecisionSource(DecisionSource.REUSED);
        return Optional.of(ContentClassification.semantic(reuseVerdict.get(), "reused")
                .withProvenance(reused));
    }

    /** The verdict a persisted result would contribute if reuse were allowed. */
    private Optional<Verdict> reuseVerdictOf(AiValidationResult result, Instant now) {
        if (result.status() == AiValidationStatus.FAILED
                || result.detectedRiskTypes().contains(AiRiskType.NOT_A_PARKING_SPOT)) {
            return Optional.of(Verdict.NOT_A_PARKING_SPOT);
        }
        if (result.status() == AiValidationStatus.PASSED) {
            return Optional.of(Verdict.LIKELY_PARKING);
        }
        if (result.status() == AiValidationStatus.WARNING) {
            boolean infra = result.findings().stream().anyMatch(f ->
                    f.message() != null
                            && f.message().startsWith(DeterministicAiValidator.VISION_OUTCOME_INFRA_PREFIX));
            if (infra) {
                return Optional.empty();
            }
            boolean semanticUncertain = result.findings().stream().anyMatch(f ->
                    DeterministicAiValidator.VISION_OUTCOME_SEMANTIC_UNCERTAIN.equals(f.message()));
            if (semanticUncertain) {
                Duration age = Duration.between(result.createdAt(), now);
                if (age.compareTo(properties.getSemanticUncertainReuseTtl()) <= 0) {
                    return Optional.of(Verdict.UNCERTAIN);
                }
            }
        }
        return Optional.empty();
    }

    /** True when the persisted result's version tuple equals the current configuration. */
    private boolean currentVersionMatches(ModerationProvenance persisted) {
        if (persisted == null || !persisted.hasCompleteVersionTuple()) {
            return false;
        }
        return persisted.sameVersionTuple(currentVersionTemplate());
    }

    /** Current version tuple (no hash/confidence/identity/source) for reuse comparison. */
    private ModerationProvenance currentVersionTemplate() {
        return new ModerationProvenance(null, providerClient.providerId(), providerClient.modelId(),
                providerClient.modelVersion(), properties.getPromptVersion(),
                properties.getPolicyVersion(), properties.getThresholdVersion(),
                null, null, null, null);
    }

    /**
     * Deterministic logical request identity: SHA-256 over the canonical image hash and
     * the full version tuple plus the normalized claimed region. Same image + same
     * versions + same region ⇒ same identity; a version bump ⇒ a new identity.
     */
    private String computeRequestIdentity(String canonicalImageHash, ClaimedRegion region) {
        String regionKey = region == null
                ? "none"
                : String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f",
                        region.x(), region.y(), region.width(), region.height());
        String recipe = String.join("|",
                REQUEST_IDENTITY_VERSION,
                canonicalImageHash,
                providerClient.providerId(),
                providerClient.modelId(),
                providerClient.modelVersion(),
                properties.getPromptVersion(),
                properties.getPolicyVersion(),
                properties.getThresholdVersion(),
                "region=" + regionKey);
        return sha256Hex(recipe.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private ContentClassification failClosed(UUID mediaId, Instant start, String reason,
                                             RuntimeException cause) {
        Duration duration = Duration.between(start, clock.instant());
        metrics.recordFailClosed(reason);
        metrics.recordOutcome("FAIL_CLOSED", duration);
        log.warn("Vision validation failed closed (UNCERTAIN) for media {}: reason={} cause={} durationMs={}",
                mediaId, reason, cause.getClass().getSimpleName(), duration.toMillis());
        ModerationProvenance provenance =
                currentVersionTemplate().withDecisionSource(DecisionSource.INFRASTRUCTURE);
        return ContentClassification.infrastructure(reason).withProvenance(provenance);
    }
}
