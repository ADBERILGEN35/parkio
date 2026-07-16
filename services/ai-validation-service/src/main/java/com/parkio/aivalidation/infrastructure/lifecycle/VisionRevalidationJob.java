package com.parkio.aivalidation.infrastructure.lifecycle;

import com.parkio.aivalidation.application.AiValidationApplicationService;
import com.parkio.aivalidation.application.port.AiValidationResultRepository;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.AiValidationStatus;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
import com.parkio.aivalidation.infrastructure.config.VisionProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded recovery sweep: revalidates media whose latest result is WARNING tagged as
 * infrastructure fail-closed. Does not retry genuine SEMANTIC_UNCERTAIN forever.
 *
 * <p>Quota-aware: batch size and fixed delay are configurable; concurrency remains 1
 * via the application service / classifier single-flight.
 */
@Component
@ConditionalOnProperty(name = "parkio.ai.vision.provider", havingValue = "gemini")
@ConditionalOnProperty(name = "parkio.ai.vision.revalidation.enabled", havingValue = "true",
        matchIfMissing = true)
public class VisionRevalidationJob {

    private static final Logger log = LoggerFactory.getLogger(VisionRevalidationJob.class);

    private final AiValidationResultRepository results;
    private final AiValidationApplicationService applicationService;
    private final VisionProperties properties;
    private final Clock clock;

    public VisionRevalidationJob(AiValidationResultRepository results,
                                 AiValidationApplicationService applicationService,
                                 VisionProperties properties,
                                 Clock clock) {
        this.results = results;
        this.applicationService = applicationService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${parkio.ai.vision.revalidation.fixed-delay-ms:300000}")
    public void sweep() {
        Instant now = clock.instant();
        Instant newest = now.minus(properties.getRevalidation().getMinAge());
        Instant oldest = now.minus(properties.getRevalidation().getMaxAge());
        int limit = properties.getRevalidation().getBatchSize();

        List<AiValidationResult> warnings = results.findByStatusAndCreatedAtBetween(
                AiValidationStatus.WARNING, oldest, newest, Math.max(limit * 5, limit));

        Set<UUID> mediaSeen = new LinkedHashSet<>();
        int attempted = 0;
        for (AiValidationResult result : warnings.stream()
                .sorted(Comparator.comparing(AiValidationResult::createdAt))
                .toList()) {
            if (!isInfrastructureFailure(result)) {
                continue;
            }
            if (!mediaSeen.add(result.mediaId())) {
                continue;
            }
            try {
                applicationService.revalidateInfrastructureFailure(result.mediaId());
                attempted++;
            } catch (RuntimeException ex) {
                log.warn("Vision revalidation failed for media {}: {}",
                        result.mediaId(), ex.getClass().getSimpleName());
            }
            if (attempted >= limit) {
                break;
            }
        }
        if (attempted > 0) {
            log.info("Vision revalidation sweep attempted {} media", attempted);
        }
    }

    static boolean isInfrastructureFailure(AiValidationResult result) {
        return result.status() == AiValidationStatus.WARNING
                && result.findings().stream().anyMatch(f ->
                f.message() != null
                        && f.message().startsWith(DeterministicAiValidator.VISION_OUTCOME_INFRA_PREFIX));
    }
}
