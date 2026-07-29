package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.port.DecisionAuthorityObserverPort;
import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.authority.AuthorityAlgorithmVersion;
import com.parkio.parking.decision.authority.AuthorityEligibilityReason;
import com.parkio.parking.decision.authority.AuthorityFallbackReason;
import com.parkio.parking.decision.policy.ShadowDecisionPolicyConfig;
import com.parkio.parking.domain.ParkingSpotStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Bounded Micrometer metrics for WP-05.8 controlled authority.
 * Never tags spot/evaluation/audit IDs, scores, or canary buckets.
 */
@Component
public class DecisionAuthorityMetrics implements DecisionAuthorityObserverPort {

    private static final String POLICY = ShadowDecisionPolicyConfig.POLICY_VERSION.value();
    private static final String ALGO = AuthorityAlgorithmVersion.V1;

    private final Map<AuthorityEligibilityReason, Counter> considered;
    private final Counter selected;
    private final Map<PublicationDisposition, Counter> appliedByDisposition;
    private final Map<ParkingSpotStatus, Counter> appliedByStatus;
    private final Map<AuthorityFallbackReason, Counter> fallbacks;
    private final Counter auditFailure;
    private final Counter engineFailure;
    private final Timer duration;

    public DecisionAuthorityMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        this.considered = enumCounters(
                registry,
                AuthorityEligibilityReason.class,
                "parkio.parking.decision.authority.considered",
                "Authority eligibility evaluations considered",
                "reason");
        this.selected = Counter.builder("parkio.parking.decision.authority.selected")
                .description("Evaluations selected into the authority canary cohort")
                .tag("policy_version", POLICY)
                .tag("authority_algorithm_version", ALGO)
                .register(registry);
        this.appliedByDisposition = enumCounters(
                registry,
                PublicationDisposition.class,
                "parkio.parking.decision.authority.applied",
                "Authoritative decisions successfully applied",
                "disposition");
        this.appliedByStatus = enumCounters(
                registry,
                ParkingSpotStatus.class,
                "parkio.parking.decision.authority.applied_status",
                "ParkingSpot status after authoritative apply",
                "status");
        this.fallbacks = enumCounters(
                registry,
                AuthorityFallbackReason.class,
                "parkio.parking.decision.authority.fallback",
                "Authority attempts routed to legacy or failed closed",
                "reason");
        this.auditFailure = Counter.builder("parkio.parking.decision.authority.audit_failure")
                .description("Authoritative audit persistence failures")
                .tag("policy_version", POLICY)
                .tag("authority_algorithm_version", ALGO)
                .register(registry);
        this.engineFailure = Counter.builder("parkio.parking.decision.authority.engine_failure")
                .description("Authority Decision Engine / evidence technical failures")
                .tag("policy_version", POLICY)
                .tag("authority_algorithm_version", ALGO)
                .register(registry);
        this.duration = Timer.builder("parkio.parking.decision.authority.duration")
                .description("Controlled authority orchestration duration")
                .tag("policy_version", POLICY)
                .tag("authority_algorithm_version", ALGO)
                .register(registry);
    }

    @Override
    public void recordConsidered(AuthorityEligibilityReason reason) {
        considered.get(reason).increment();
    }

    @Override
    public void recordSelected() {
        selected.increment();
    }

    @Override
    public void recordApplied(PublicationDisposition disposition, ParkingSpotStatus appliedStatus) {
        appliedByDisposition.get(disposition).increment();
        appliedByStatus.get(appliedStatus).increment();
    }

    @Override
    public void recordFallback(AuthorityFallbackReason reason) {
        fallbacks.get(reason).increment();
    }

    @Override
    public void recordAuditFailure() {
        auditFailure.increment();
    }

    @Override
    public void recordEngineFailure() {
        engineFailure.increment();
    }

    @Override
    public void recordDuration(Duration duration) {
        this.duration.record(duration);
    }

    private static <E extends Enum<E>> Map<E, Counter> enumCounters(
            MeterRegistry registry,
            Class<E> type,
            String name,
            String description,
            String tagKey) {
        Map<E, Counter> map = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            map.put(
                    value,
                    Counter.builder(name)
                            .description(description)
                            .tag("policy_version", POLICY)
                            .tag("authority_algorithm_version", ALGO)
                            .tag(tagKey, value.name())
                            .register(registry));
        }
        return map;
    }
}