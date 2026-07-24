package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.idempotency.IdempotencyException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/** Bounded operational outcomes for the atomic community-claim use case. */
@Component
public class CommunityClaimMetrics {

    static final String METRIC_NAME = "parkio.parking.community.claim.outcomes";

    private static final Logger log = LoggerFactory.getLogger(CommunityClaimMetrics.class);

    private final Map<Outcome, Counter> counters;

    public CommunityClaimMetrics(MeterRegistry registry) {
        EnumMap<Outcome, Counter> registered = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            registered.put(outcome, Counter.builder(METRIC_NAME)
                    .description("Atomic community claim outcomes")
                    .tag("outcome", outcome.tag)
                    .register(registry));
        }
        this.counters = Map.copyOf(registered);
    }

    public void recordSuccess(UUID spotId, boolean replayed) {
        Outcome outcome = replayed ? Outcome.REPLAYED : Outcome.COMMITTED;
        record(outcome, spotId);
    }

    public void recordFailure(UUID spotId, RuntimeException failure) {
        record(classify(failure), spotId);
    }

    private void record(Outcome outcome, UUID spotId) {
        counters.get(outcome).increment();
        log.info("Community claim outcome={} spotId={}", outcome.tag, spotId);
    }

    private static Outcome classify(RuntimeException failure) {
        if (failure instanceof ParkingException parkingException) {
            ParkingErrorCode code = parkingException.errorCode();
            return switch (code) {
                case ACTIVE_PARKING_SESSION_EXISTS -> Outcome.ACTIVE_SESSION_CONFLICT;
                case SPOT_EXPIRED -> Outcome.EXPIRED;
                case SPOT_NOT_CLAIMABLE -> Outcome.NOT_CLAIMABLE;
                case OWNER_CANNOT_CLAIM -> Outcome.OWNER_FORBIDDEN;
                case SPOT_NOT_FOUND -> Outcome.NOT_FOUND;
                default -> Outcome.DOMAIN_FAILURE;
            };
        }
        if (failure instanceof IdempotencyException idempotencyException) {
            return switch (idempotencyException.code()) {
                case "IDEMPOTENCY_KEY_CONFLICT" -> Outcome.IDEMPOTENCY_CONFLICT;
                case "IDEMPOTENCY_REQUEST_IN_PROGRESS" -> Outcome.IDEMPOTENCY_IN_PROGRESS;
                default -> Outcome.REQUEST_REJECTED;
            };
        }
        if (failure instanceof ObjectOptimisticLockingFailureException) {
            return Outcome.OPTIMISTIC_CONFLICT;
        }
        if (failure instanceof DataIntegrityViolationException) {
            return Outcome.INTEGRITY_CONFLICT;
        }
        return Outcome.UNEXPECTED;
    }

    private enum Outcome {
        COMMITTED("committed"),
        REPLAYED("replayed"),
        ACTIVE_SESSION_CONFLICT("active_session_conflict"),
        OPTIMISTIC_CONFLICT("optimistic_conflict"),
        INTEGRITY_CONFLICT("integrity_conflict"),
        EXPIRED("expired"),
        NOT_CLAIMABLE("not_claimable"),
        OWNER_FORBIDDEN("owner_forbidden"),
        NOT_FOUND("not_found"),
        IDEMPOTENCY_CONFLICT("idempotency_conflict"),
        IDEMPOTENCY_IN_PROGRESS("idempotency_in_progress"),
        REQUEST_REJECTED("request_rejected"),
        DOMAIN_FAILURE("domain_failure"),
        UNEXPECTED("unexpected");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }
}
