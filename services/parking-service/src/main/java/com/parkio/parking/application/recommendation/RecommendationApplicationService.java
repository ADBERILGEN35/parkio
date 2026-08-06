package com.parkio.parking.application.recommendation;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.MunicipalFacilityQueryService.FacilityView;
import com.parkio.parking.application.ParkingApplicationService;
import com.parkio.parking.application.command.SearchNearbyQuery;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.domain.place.Destination;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Dual-inventory recommendation orchestration (WP-SPA-05).
 *
 * <p>Composes existing community and municipal nearby paths into a deterministic
 * distance-ordered {@link ParkingCandidate} list. Does not implement weighted
 * ranking, favourite boosts, or recents (WP-SPA-06 / WP-SPA-07).
 *
 * <p>No response cache — nearby inventories are occupancy-sensitive and the
 * existing discovery paths are uncached.
 */
@Service
public class RecommendationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationApplicationService.class);

    /** Per-channel overfetch so neither inventory starves the merge. */
    static final int CHANNEL_FETCH_MULTIPLIER = 2;
    static final int MAX_COMMUNITY_FETCH = 50;
    static final int MAX_MUNICIPAL_FETCH = 100;

    private static final Comparator<ParkingCandidate> BASELINE_ORDER =
            Comparator.comparingInt(ParkingCandidate::distanceMeters)
                    .thenComparing(c -> c.channel().name())
                    .thenComparing(ParkingCandidate::refId);

    private final ParkingApplicationService communityParking;
    private final MunicipalFacilityQueryService municipalFacilities;
    private final Clock clock;
    private final RecommendationMetrics metrics;
    private final Executor executor;

    @Autowired
    public RecommendationApplicationService(
            ParkingApplicationService communityParking,
            MunicipalFacilityQueryService municipalFacilities,
            Clock clock,
            RecommendationMetrics metrics) {
        this(
                communityParking,
                municipalFacilities,
                clock,
                metrics,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    RecommendationApplicationService(
            ParkingApplicationService communityParking,
            MunicipalFacilityQueryService municipalFacilities,
            Clock clock,
            RecommendationMetrics metrics,
            Executor executor) {
        this.communityParking = communityParking;
        this.municipalFacilities = municipalFacilities;
        this.clock = clock;
        this.metrics = metrics;
        this.executor = executor;
    }

    public RecommendationResult recommend(RecommendationQuery raw) {
        long started = System.nanoTime();
        RecommendationQuery query = normalize(raw);
        Destination destination = query.destination();

        ChannelFetch communityFetch = query.includeCommunity()
                ? fetchCommunityAsync(query)
                : ChannelFetch.disabled();
        ChannelFetch municipalFetch = query.includeMunicipal()
                ? fetchMunicipalAsync(query)
                : ChannelFetch.disabled();

        communityFetch.await();
        municipalFetch.await();

        int requestedFailures = 0;
        int requestedSuccesses = 0;
        if (query.includeCommunity()) {
            if (communityFetch.failed()) {
                requestedFailures++;
            } else {
                requestedSuccesses++;
            }
        }
        if (query.includeMunicipal()) {
            if (municipalFetch.failed()) {
                requestedFailures++;
            } else {
                requestedSuccesses++;
            }
        }

        if (requestedFailures > 0 && requestedSuccesses == 0) {
            metrics.record(
                    true,
                    true,
                    0,
                    0,
                    0,
                    query.radiusMeters(),
                    query.limit(),
                    System.nanoTime() - started);
            log.warn(
                    "recommendation both inventories unavailable radiusBucket={} limitBucket={}",
                    RecommendationMetrics.radiusBucket(query.radiusMeters()),
                    RecommendationMetrics.limitBucket(query.limit()));
            throw new ParkingException(
                    ParkingErrorCode.RECOMMENDATION_INVENTORIES_UNAVAILABLE,
                    "Parking inventories are temporarily unavailable.");
        }

        List<ParkingCandidate> merged = new ArrayList<>();
        merged.addAll(communityFetch.candidates());
        merged.addAll(municipalFetch.candidates());
        merged.sort(BASELINE_ORDER);

        List<ParkingCandidate> limited = new ArrayList<>();
        int order = 0;
        for (ParkingCandidate candidate : merged) {
            if (limited.size() >= query.limit()) {
                break;
            }
            limited.add(ParkingCandidateMapper.withBaselineOrder(candidate, order++));
        }

        boolean partial = requestedFailures > 0;
        List<RecommendationReason> warnings = partial
                ? List.of(RecommendationReason.of(RecommendationReasonCode.INVENTORY_DEGRADED))
                : List.of();

        RecommendationResult result = new RecommendationResult(
                destination,
                clock.instant(),
                partial,
                communityFetch.status(),
                municipalFetch.status(),
                limited,
                warnings);

        metrics.record(
                partial,
                false,
                communityFetch.candidates().size(),
                municipalFetch.candidates().size(),
                limited.size(),
                query.radiusMeters(),
                query.limit(),
                System.nanoTime() - started);

        log.info(
                "recommendation complete partial={} community={} municipal={} results={} radiusBucket={} limitBucket={}",
                partial,
                communityFetch.status(),
                municipalFetch.status(),
                limited.size(),
                RecommendationMetrics.radiusBucket(query.radiusMeters()),
                RecommendationMetrics.limitBucket(query.limit()));

        return result;
    }

    static RecommendationQuery normalize(RecommendationQuery raw) {
        if (raw == null) {
            throw new ParkingException(ParkingErrorCode.INVALID_DESTINATION, "Destination is required.");
        }
        if (raw.destination() == null) {
            throw new ParkingException(ParkingErrorCode.INVALID_DESTINATION, "Destination is required.");
        }
        int radius = raw.radiusMeters() <= 0
                ? RecommendationQuery.DEFAULT_RADIUS_METERS
                : raw.radiusMeters();
        if (radius < RecommendationQuery.MIN_RADIUS_METERS
                || radius > RecommendationQuery.MAX_RADIUS_METERS) {
            throw new ParkingException(
                    ParkingErrorCode.INVALID_RECOMMENDATION_RADIUS,
                    "radiusMeters must be between "
                            + RecommendationQuery.MIN_RADIUS_METERS
                            + " and "
                            + RecommendationQuery.MAX_RADIUS_METERS);
        }
        int limit = raw.limit() <= 0 ? RecommendationQuery.DEFAULT_LIMIT : raw.limit();
        if (limit < RecommendationQuery.MIN_LIMIT || limit > RecommendationQuery.MAX_LIMIT) {
            throw new ParkingException(
                    ParkingErrorCode.INVALID_RECOMMENDATION_LIMIT,
                    "limit must be between "
                            + RecommendationQuery.MIN_LIMIT
                            + " and "
                            + RecommendationQuery.MAX_LIMIT);
        }
        if (!raw.includeCommunity() && !raw.includeMunicipal()) {
            throw new ParkingException(
                    ParkingErrorCode.INVENTORY_SELECTION_REQUIRED,
                    "At least one inventory channel must be enabled.");
        }
        return new RecommendationQuery(
                raw.requesterUserId(),
                raw.destination(),
                radius,
                limit,
                raw.includeCommunity(),
                raw.includeMunicipal());
    }

    static int channelFetchLimit(int globalLimit, int max) {
        long scaled = (long) globalLimit * CHANNEL_FETCH_MULTIPLIER;
        return (int) Math.min(Math.max(scaled, globalLimit), max);
    }

    private ChannelFetch fetchCommunityAsync(RecommendationQuery query) {
        CompletableFuture<List<ParkingCandidate>> future = CompletableFuture.supplyAsync(
                () -> {
                    int fetchLimit = channelFetchLimit(query.limit(), MAX_COMMUNITY_FETCH);
                    SearchNearbyQuery nearby = new SearchNearbyQuery(
                            query.requesterUserId(),
                            query.destination().latitude(),
                            query.destination().longitude(),
                            (double) query.radiusMeters(),
                            fetchLimit);
                    List<ParkingSpot> spots = communityParking.searchNearby(nearby);
                    return spots.stream()
                            .map(spot -> ParkingCandidateMapper.fromCommunity(
                                    spot,
                                    query.destination().latitude(),
                                    query.destination().longitude()))
                            .toList();
                },
                executor);
        return ChannelFetch.pending(future, ParkingCandidateChannel.COMMUNITY_SPOT);
    }

    private ChannelFetch fetchMunicipalAsync(RecommendationQuery query) {
        CompletableFuture<List<ParkingCandidate>> future = CompletableFuture.supplyAsync(
                () -> {
                    int fetchLimit = channelFetchLimit(query.limit(), MAX_MUNICIPAL_FETCH);
                    List<FacilityView> facilities = municipalFacilities.nearby(
                            query.destination().latitude(),
                            query.destination().longitude(),
                            query.radiusMeters(),
                            fetchLimit);
                    return facilities.stream()
                            .map(facility -> ParkingCandidateMapper.fromMunicipal(
                                    facility,
                                    query.destination().latitude(),
                                    query.destination().longitude()))
                            .toList();
                },
                executor);
        return ChannelFetch.pending(future, ParkingCandidateChannel.MUNICIPAL_FACILITY);
    }

    private static final class ChannelFetch {
        private final InventoryChannelStatus disabledStatus;
        private final CompletableFuture<List<ParkingCandidate>> future;
        private InventoryChannelStatus status;
        private List<ParkingCandidate> candidates = List.of();
        private boolean failed;

        private ChannelFetch(
                InventoryChannelStatus disabledStatus, CompletableFuture<List<ParkingCandidate>> future) {
            this.disabledStatus = disabledStatus;
            this.future = future;
            this.status = disabledStatus != null ? disabledStatus : InventoryChannelStatus.EMPTY;
        }

        static ChannelFetch disabled() {
            return new ChannelFetch(InventoryChannelStatus.DISABLED, null);
        }

        static ChannelFetch pending(
                CompletableFuture<List<ParkingCandidate>> future, ParkingCandidateChannel channel) {
            ChannelFetch fetch = new ChannelFetch(null, future);
            // channel retained only for clarity in stack traces via future
            return fetch;
        }

        void await() {
            if (future == null) {
                return;
            }
            try {
                List<ParkingCandidate> result = future.join();
                this.candidates = result == null ? List.of() : List.copyOf(result);
                this.status = candidates.isEmpty()
                        ? InventoryChannelStatus.EMPTY
                        : InventoryChannelStatus.AVAILABLE;
                this.failed = false;
            } catch (RuntimeException ex) {
                Throwable cause = ex instanceof CompletionException && ex.getCause() != null
                        ? ex.getCause()
                        : ex;
                log.warn(
                        "recommendation inventory channel failed type={}",
                        cause.getClass().getSimpleName());
                this.candidates = List.of();
                this.status = InventoryChannelStatus.DEGRADED;
                this.failed = true;
            }
        }

        InventoryChannelStatus status() {
            return status;
        }

        List<ParkingCandidate> candidates() {
            return candidates;
        }

        boolean failed() {
            return failed;
        }
    }
}
