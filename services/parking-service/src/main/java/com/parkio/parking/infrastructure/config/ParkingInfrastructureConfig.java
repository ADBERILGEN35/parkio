package com.parkio.parking.infrastructure.config;

import com.parkio.parking.application.DecisionAuthoritySettings;
import com.parkio.parking.application.DecisionShadowOrchestrator;
import com.parkio.parking.application.ExposureShadowSettings;
import com.parkio.parking.application.ParkingSearchSettings;
import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.application.MunicipalSourceSlaPolicy;
import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.application.port.MunicipalSourceLinkRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.application.port.DecisionAuditWriteObserver;
import com.parkio.parking.application.port.DecisionShadowObserverPort;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import com.parkio.parking.decision.application.EvidenceCollectionService;
import com.parkio.parking.decision.policy.DecisionEngine;
import com.parkio.parking.decision.port.DecisionAuditPort;
import com.parkio.parking.decision.port.EvidenceCollectionPort;
import com.parkio.parking.domain.ModerationPolicy;
import com.parkio.parking.domain.ParkingSessionStalePolicy;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Infrastructure wiring: a system-UTC {@link Clock}, the application's
 * {@link ParkingSearchSettings} derived from properties (so the application layer
 * stays free of Spring config types), and scheduling for the outbox relay poller.
 */
@Configuration
@EnableConfigurationProperties({
        ParkingProperties.class, GeocodingProperties.class, MunicipalSourceProperties.class,
        RegistryProperties.class
})
@EnableScheduling
public class ParkingInfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public MunicipalFacilitySyncService municipalFacilitySyncService(
            List<MunicipalParkingSourceAdapter> adapters,
            MunicipalDataSourceRepository sources,
            MunicipalFacilityRepository facilities,
            MunicipalSourceLinkRepository links,
            MunicipalOccupancySnapshotRepository snapshots,
            MunicipalSourceSyncRunRepository runs,
            Clock clock) {
        return new MunicipalFacilitySyncService(adapters, sources, facilities, links, snapshots, runs, clock);
    }

    @Bean
    public MunicipalSourceHealthService municipalSourceHealthService(
            MunicipalDataSourceRepository sources,
            MunicipalSourceSyncRunRepository runs,
            Clock clock,
            MunicipalSourceProperties properties) {
        MunicipalSourceProperties.Sla sla = properties.getSla();
        MunicipalSourceSlaPolicy.Thresholds thresholds = new MunicipalSourceSlaPolicy.Thresholds(
                sla.getWarningConsecutiveFailures(),
                sla.getCriticalConsecutiveFailures(),
                sla.getWarningSecondsSinceSuccess(),
                sla.getCriticalSecondsSinceSuccess(),
                sla.getStaleRunningAfterSeconds(),
                sla.getRecoveringWindowSeconds());
        return new MunicipalSourceHealthService(
                sources,
                runs,
                clock,
                thresholds,
                properties.isEnabled(),
                properties.getIzum().isEnabled(),
                properties.getIzum().isSchedulerEnabled(),
                IzumMunicipalParkingAdapter.SOURCE_KEY);
    }

    @Bean
    public MunicipalFacilityQueryService municipalFacilityQueryService(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            MunicipalSourceProperties municipalSourceProperties,
            IzelmanProperties izelmanProperties,
            Clock clock) {
        return new MunicipalFacilityQueryService(
                facilities, snapshots, municipalSourceProperties, izelmanProperties, clock);
    }

    @Bean
    public ParkingSearchSettings parkingSearchSettings(ParkingProperties properties) {
        return new ParkingSearchSettings(
                properties.getSearch().getDefaultRadiusMeters(),
                properties.getSearch().getDefaultResultLimit(),
                properties.getSearch().getMaxRadiusMeters(),
                properties.getSearch().getMaxResultLimit());
    }

    @Bean
    public ModerationPolicy moderationPolicy(ParkingProperties properties) {
        ParkingProperties.Moderation moderation = properties.getModeration();
        return new ModerationPolicy(
                moderation.getActiveDuration(),
                moderation.getValidationTimeout(),
                moderation.getValidationRetryBackoff(),
                moderation.getMaxValidationAttempts(),
                moderation.getReviewTimeout(),
                moderation.getMaxPublishableAge());
    }

    @Bean
    public ParkingSessionStalePolicy parkingSessionStalePolicy(ParkingProperties properties) {
        ParkingProperties.Session session = properties.getSession();
        return new ParkingSessionStalePolicy(
                session.getConfirmAfter(),
                session.getReminder2After(),
                session.getAutoCompleteAfter());
    }

    /**
     * Pure Decision Engine + fail-safe shadow orchestrator. Enabled only when
     * {@code parkio.parking.decision.shadow-enabled=true} (default false).
     * Successful evaluations append immutable audit snapshots via {@link DecisionAuditPort}.
     */
    @Bean
    public DecisionShadowOrchestrator decisionShadowOrchestrator(
            ParkingProperties properties,
            DecisionShadowObserverPort observer,
            DecisionAuditPort auditPort,
            DecisionAuditWriteObserver auditWriteObserver) {
        return new DecisionShadowOrchestrator(
                properties.getDecision().isShadowEnabled(),
                new DecisionEngine(),
                new EvidenceCollectionService(),
                observer,
                auditPort,
                auditWriteObserver);
    }

    /**
     * Validated WP-05.8 authority settings. Defaults disabled / 0% canary.
     * Unsupported policy versions fail startup safely.
     */
    @Bean
    public DecisionAuthoritySettings decisionAuthoritySettings(ParkingProperties properties) {
        ParkingProperties.Authority authority = properties.getDecision().getAuthority();
        return new DecisionAuthoritySettings(
                authority.isEnabled(),
                authority.getCanaryPercentage(),
                authority.getPolicyVersion());
    }

    @Bean
    public DecisionEngine decisionEngine() {
        return new DecisionEngine();
    }

    @Bean
    public EvidenceCollectionPort evidenceCollectionPort() {
        return new EvidenceCollectionService();
    }

    @Bean
    public ExposureShadowSettings exposureShadowSettings(ParkingProperties properties) {
        ParkingProperties.ExposureShadow exposure = properties.getExposureShadow();
        return new ExposureShadowSettings(
                exposure.isEnabled(),
                exposure.getSamplePercent(),
                exposure.getTimeBudgetMillis());
    }
}