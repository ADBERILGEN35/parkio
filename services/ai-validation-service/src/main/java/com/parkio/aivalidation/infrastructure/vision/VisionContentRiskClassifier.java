package com.parkio.aivalidation.infrastructure.vision;

import com.parkio.aivalidation.application.port.AiValidationResultRepository;
import com.parkio.aivalidation.domain.AiRiskType;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.AiValidationStatus;
import com.parkio.aivalidation.domain.ContentClassification;
import com.parkio.aivalidation.domain.ContentRiskClassifier;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
import com.parkio.aivalidation.infrastructure.config.VisionProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
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
            ContentClassification classification = applyConfidencePolicy(analysis);
            Duration duration = Duration.between(start, clock.instant());
            metrics.recordOutcome(classification.verdict().name(), duration);
            metrics.markSuccess();
            log.info("Vision validation completed for media {}: verdict={} outcome={} "
                            + "confidence={} reasonCode={} durationMs={}",
                    mediaId, classification.verdict(), classification.outcomeKind(),
                    String.format(Locale.ROOT, "%.2f", analysis.confidence()),
                    analysis.reasonCode(), duration.toMillis());
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
    static final Set<String> CONCRETE_REJECT_REASON_CODES = Set.of(
            "NO_PLAUSIBLE_SPACE",
            "TARGET_PHYSICALLY_BLOCKED",
            "CLEARLY_RESTRICTED_AREA",
            "UNRELATED_SUBJECT",
            "SCREENSHOT_OR_SYNTHETIC",
            "TOO_DARK_OR_BLURRY");

    static final Set<String> FORCE_UNCERTAIN_REASON_CODES = Set.of(
            "NEARBY_BARRIER_NOT_BLOCKING_TARGET",
            "LEGALITY_UNCERTAIN",
            "POSSIBLE_SPACE_UNCERTAIN_WIDTH",
            "POSSIBLE_SPACE_UNCLEAR_ACCESS",
            "WHOLE_IMAGE_NO_REGION");

    private ContentClassification applyConfidencePolicy(VisionProviderClient.VisionAnalysis analysis) {
        String reasonCode = analysis.reasonCode() == null ? "OTHER" : analysis.reasonCode();
        Verdict verdict = switch (analysis.verdict()) {
            case "LIKELY_PARKING" -> analysis.confidence() >= properties.getAcceptConfidence()
                    ? Verdict.LIKELY_PARKING
                    : Verdict.UNCERTAIN;
            case "NOT_A_PARKING_SPOT" -> {
                if (FORCE_UNCERTAIN_REASON_CODES.contains(reasonCode)
                        || !CONCRETE_REJECT_REASON_CODES.contains(reasonCode)) {
                    yield Verdict.UNCERTAIN;
                }
                yield analysis.confidence() >= properties.getRejectConfidence()
                        ? Verdict.NOT_A_PARKING_SPOT
                        : Verdict.UNCERTAIN;
            }
            default -> Verdict.UNCERTAIN;
        };
        return ContentClassification.semantic(
                verdict,
                reasonCode,
                analysis.claimedRegionAssessment(),
                analysis.vehicleFitEstimate(),
                analysis.obstructionAssessment(),
                analysis.legalityAccessAssessment());
    }

    private Optional<ContentClassification> reusableClassification(UUID mediaId) {
        return results.findByMediaId(mediaId).stream()
                .max(Comparator.comparing(AiValidationResult::createdAt))
                .flatMap(result -> mapReusable(result, clock.instant()));
    }

    private Optional<ContentClassification> mapReusable(AiValidationResult result, Instant now) {
        if (result.status() == AiValidationStatus.FAILED
                || result.detectedRiskTypes().contains(AiRiskType.NOT_A_PARKING_SPOT)) {
            return Optional.of(ContentClassification.semantic(Verdict.NOT_A_PARKING_SPOT, "reused"));
        }
        if (result.status() == AiValidationStatus.PASSED) {
            return Optional.of(ContentClassification.semantic(Verdict.LIKELY_PARKING, "reused"));
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
                    return Optional.of(ContentClassification.semantic(Verdict.UNCERTAIN, "reused"));
                }
            }
        }
        return Optional.empty();
    }

    private ContentClassification failClosed(UUID mediaId, Instant start, String reason,
                                             RuntimeException cause) {
        Duration duration = Duration.between(start, clock.instant());
        metrics.recordFailClosed(reason);
        metrics.recordOutcome("FAIL_CLOSED", duration);
        log.warn("Vision validation failed closed (UNCERTAIN) for media {}: reason={} cause={} durationMs={}",
                mediaId, reason, cause.getClass().getSimpleName(), duration.toMillis());
        return ContentClassification.infrastructure(reason);
    }
}
