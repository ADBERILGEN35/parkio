package com.parkio.parking.application;

import com.parkio.parking.application.exposure.ExposureShadowProcessingResult;
import com.parkio.parking.application.exposure.SearchExposureEvidenceFactory;
import com.parkio.parking.application.port.ExposureShadowObserverPort;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.exposure.ExposureQueryContext;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fail-safe request-path exposure shadow orchestrator. Never mutates search output.
 */
@Component
public class ExposureShadowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ExposureShadowOrchestrator.class);

    private final ExposureShadowSettings settings;
    private final ExposureShadowApplicationService applicationService;
    private final ExposureShadowObserverPort observer;

    public ExposureShadowOrchestrator(
            ExposureShadowSettings settings,
            ExposureShadowApplicationService applicationService,
            ExposureShadowObserverPort observer) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.applicationService = Objects.requireNonNull(applicationService, "applicationService");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public void maybeEvaluateNearbySearch(
            List<ParkingSpot> legacyResults,
            double queryLatitude,
            double queryLongitude,
            double radiusMeters,
            int limit,
            boolean authenticated) {
        if (!settings.enabled()) {
            observer.recordRequestSkipped("DISABLED");
            return;
        }
        try {
            ExposureQueryContext queryContext = SearchExposureEvidenceFactory.queryContext(
                    queryLatitude, queryLongitude, radiusMeters, limit, authenticated);
            if (!SearchExposureEvidenceFactory.deterministicSample(queryContext, settings.samplePercent())) {
                observer.recordRequestSkipped("NOT_SAMPLED");
                return;
            }
            observer.recordRequestSampled();
            ExposureShadowProcessingResult result = applicationService.evaluateNearbySearch(
                    legacyResults,
                    queryLatitude,
                    queryLongitude,
                    radiusMeters,
                    limit,
                    authenticated,
                    settings.timeBudgetMillis());
            if (result.status() == ExposureShadowProcessingResult.Status.FAILED) {
                log.debug(
                        "Exposure shadow evaluation failed stage={}",
                        result.failureStage().map(Enum::name).orElse("UNKNOWN"));
            }
        } catch (RuntimeException ex) {
            log.debug("Exposure shadow orchestration failed", ex);
            observer.recordEvaluationFailure(com.parkio.parking.application.exposure.ExposureShadowFailureStage.EVALUATION_FAILURE);
        }
    }
}
