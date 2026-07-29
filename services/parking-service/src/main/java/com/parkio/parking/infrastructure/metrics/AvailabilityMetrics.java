package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.port.AvailabilityObserverPort;
import com.parkio.parking.availability.AvailabilityEvaluation;
import com.parkio.parking.availability.AvailabilityFreshness;
import com.parkio.parking.availability.AvailabilityState;
import com.parkio.parking.availability.policy.AvailabilityPolicyConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Micrometer adapter for Availability Engine observability.
 *
 * <p>Tags are bounded enums only — never spot IDs or exact scores.
 */
@Component
public class AvailabilityMetrics implements AvailabilityObserverPort {

    private static final String POLICY_VERSION = AvailabilityPolicyConfig.POLICY_VERSION.value();

    private final Counter evaluations;
    private final Timer duration;
    private final Map<AvailabilityState, Counter> stateCounters;
    private final Map<AvailabilityFreshness, Counter> freshnessCounters;
    private final Counter expiredCounter;
    private final Counter agingCounter;

    public AvailabilityMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");

        this.evaluations = Counter.builder("parkio.parking.availability.evaluation")
                .description("Availability Engine evaluations recorded")
                .tag("policy_version", POLICY_VERSION)
                .register(registry);
        this.duration = Timer.builder("parkio.parking.availability.evaluation.duration")
                .description("Availability Engine evaluation duration")
                .tag("policy_version", POLICY_VERSION)
                .register(registry);

        EnumMap<AvailabilityState, Counter> states = new EnumMap<>(AvailabilityState.class);
        for (AvailabilityState state : AvailabilityState.values()) {
            states.put(state, Counter.builder("parkio.parking.availability.state")
                    .description("Availability evaluations by state")
                    .tag("policy_version", POLICY_VERSION)
                    .tag("state", state.name())
                    .register(registry));
        }
        this.stateCounters = Map.copyOf(states);

        EnumMap<AvailabilityFreshness, Counter> freshnessMap = new EnumMap<>(AvailabilityFreshness.class);
        for (AvailabilityFreshness freshness : AvailabilityFreshness.values()) {
            freshnessMap.put(freshness, Counter.builder("parkio.parking.availability.freshness")
                    .description("Availability evaluations by freshness band")
                    .tag("policy_version", POLICY_VERSION)
                    .tag("freshness", freshness.name())
                    .register(registry));
        }
        this.freshnessCounters = Map.copyOf(freshnessMap);

        this.expiredCounter = Counter.builder("parkio.parking.availability.expiration")
                .description("Availability evaluations where TTL is expired")
                .tag("policy_version", POLICY_VERSION)
                .tag("expired", "true")
                .register(registry);
        this.agingCounter = Counter.builder("parkio.parking.availability.aging")
                .description("Availability evaluations in aging or stale freshness bands")
                .tag("policy_version", POLICY_VERSION)
                .register(registry);
    }

    @Override
    public void recordEvaluation(AvailabilityEvaluation evaluation, Duration evalDuration) {
        Objects.requireNonNull(evaluation, "evaluation");
        evaluations.increment();
        if (evalDuration != null && !evalDuration.isNegative()) {
            duration.record(evalDuration);
        }
        stateCounters.get(evaluation.state()).increment();
        freshnessCounters.get(evaluation.freshness()).increment();
        if (evaluation.expiration().expired()) {
            expiredCounter.increment();
        }
        if (evaluation.freshness() == AvailabilityFreshness.AGING
                || evaluation.freshness() == AvailabilityFreshness.STALE) {
            agingCounter.increment();
        }
    }
}
