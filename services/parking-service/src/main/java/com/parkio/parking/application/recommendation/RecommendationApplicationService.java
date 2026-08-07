package com.parkio.parking.application.recommendation;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.MunicipalFacilityQueryService.FacilityView;
import com.parkio.parking.application.ParkingApplicationService;
import com.parkio.parking.application.command.SearchNearbyQuery;
import com.parkio.parking.application.port.FavouriteFacilityLookupPort;
import com.parkio.parking.application.recommendation.ranking.DeterministicParkingCandidateRanker;
import com.parkio.parking.application.recommendation.ranking.ParkingCandidateRanker;
import com.parkio.parking.application.recommendation.ranking.RankingContext;
import com.parkio.parking.application.recommendation.ranking.RankingMetrics;
import com.parkio.parking.application.recommendation.ranking.RankingProperties;
import com.parkio.parking.application.recommendation.ranking.RankingStatus;
import com.parkio.parking.application.recommendation.ranking.RankingVersion;
import com.parkio.parking.application.recommendation.ranking.shadow.ShadowRankingOrchestrator;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.domain.place.Destination;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Dual-inventory recommendation orchestration (WP-SPA-05 / WP-SPA-06).
 *
 * <p>Composes community and municipal nearby paths into {@link ParkingCandidate}
 * views, then applies deterministic ranking when enabled. Ranking does not own
 * inventory fetching. Favourite lookup is fail-open and never marks inventory
 * degraded.
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
    private final FavouriteFacilityLookupPort favouriteLookup;
    private final ParkingCandidateRanker ranker;
    private final RankingProperties rankingProperties;
    private final RankingMetrics rankingMetrics;
    private final ShadowRankingOrchestrator shadowRankingOrchestrator;
    private final Clock clock;
    private final RecommendationMetrics metrics;
    private final Executor executor;

    @Autowired
    public RecommendationApplicationService(
            ParkingApplicationService communityParking,
            MunicipalFacilityQueryService municipalFacilities,
            FavouriteFacilityLookupPort favouriteLookup,
            ParkingCandidateRanker ranker,
            RankingProperties rankingProperties,
            RankingMetrics rankingMetrics,
            ShadowRankingOrchestrator shadowRankingOrchestrator,
            Clock clock,
            RecommendationMetrics metrics) {
        this(
                communityParking,
                municipalFacilities,
                favouriteLookup,
                ranker,
                rankingProperties,
                rankingMetrics,
                shadowRankingOrchestrator,
                clock,
                metrics,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    RecommendationApplicationService(
            ParkingApplicationService communityParking,
            MunicipalFacilityQueryService municipalFacilities,
            FavouriteFacilityLookupPort favouriteLookup,
            ParkingCandidateRanker ranker,
            RankingProperties rankingProperties,
            RankingMetrics rankingMetrics,
            ShadowRankingOrchestrator shadowRankingOrchestrator,
            Clock clock,
            RecommendationMetrics metrics,
            Executor executor) {
        this.communityParking = communityParking;
        this.municipalFacilities = municipalFacilities;
        this.favouriteLookup = favouriteLookup;
        this.ranker = ranker;
        this.rankingProperties = rankingProperties;
        this.rankingMetrics = rankingMetrics;
        this.shadowRankingOrchestrator = shadowRankingOrchestrator;
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

        List<ParkingCandidate> withBaseline = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            withBaseline.add(ParkingCandidateMapper.withBaselineOrder(merged.get(i), i));
        }

        RankingProperties.RankingConfiguration config = rankingProperties.snapshot();
        Set<UUID> favouriteIds = lookupFavourites(query, withBaseline, config);

        ParkingCandidateRanker.RankingOutcome outcome = applyRanking(
                destination, query, withBaseline, favouriteIds, config);

        List<ParkingCandidate> limited = outcome.ranked().stream()
                .limit(query.limit())
                .toList();

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
                warnings,
                outcome.version(),
                outcome.status());

        metrics.record(
                partial,
                false,
                communityFetch.candidates().size(),
                municipalFetch.candidates().size(),
                limited.size(),
                query.radiusMeters(),
                query.limit(),
                System.nanoTime() - started);

        if (!limited.isEmpty()) {
            rankingMetrics.recordTopChannel(limited.getFirst().channel().name());
            if (limited.getFirst().score() != null) {
                rankingMetrics.recordScoreBucket(limited.getFirst().score());
            }
        }

        // WP-SPA-14: async shadow only — never blocks the public response path.
        if (shadowRankingOrchestrator != null) {
            shadowRankingOrchestrator.maybeEvaluateAsync(
                    outcome.status(),
                    outcome.version(),
                    limited,
                    partial,
                    query.radiusMeters(),
                    executor);
        }

        log.info(
                "recommendation complete partial={} community={} municipal={} results={} ranking={} radiusBucket={} limitBucket={}",
                partial,
                communityFetch.status(),
                municipalFetch.status(),
                limited.size(),
                outcome.status(),
                RecommendationMetrics.radiusBucket(query.radiusMeters()),
                RecommendationMetrics.limitBucket(query.limit()));

        return result;
    }

    private Set<UUID> lookupFavourites(
            RecommendationQuery query,
            List<ParkingCandidate> candidates,
            RankingProperties.RankingConfiguration config) {
        if (!config.enabled() || !config.favouritesEnabled()) {
            return Set.of();
        }
        Set<UUID> municipalIds = new HashSet<>();
        for (ParkingCandidate candidate : candidates) {
            if (candidate.channel() != ParkingCandidateChannel.MUNICIPAL_FACILITY) {
                continue;
            }
            try {
                municipalIds.add(UUID.fromString(candidate.refId()));
            } catch (IllegalArgumentException ignored) {
                // skip malformed ref
            }
        }
        if (municipalIds.isEmpty()) {
            return Set.of();
        }
        return favouriteLookup.favouritedMunicipalFacilityIds(query.requesterUserId(), municipalIds);
    }

    private ParkingCandidateRanker.RankingOutcome applyRanking(
            Destination destination,
            RecommendationQuery query,
            List<ParkingCandidate> withBaseline,
            Set<UUID> favouriteIds,
            RankingProperties.RankingConfiguration config) {
        long rankingStarted = System.nanoTime();
        RankingContext context = new RankingContext(
                destination,
                query.requesterUserId(),
                query.radiusMeters(),
                withBaseline,
                favouriteIds,
                config);
        try {
            ParkingCandidateRanker.RankingOutcome outcome = ranker.rank(context);
            if (outcome.status() == RankingStatus.APPLIED) {
                recordShadow(withBaseline, outcome.ranked());
            }
            rankingMetrics.recordApplied(
                    outcome.version(), outcome.status(), System.nanoTime() - rankingStarted);
            return outcome;
        } catch (RuntimeException ex) {
            log.warn(
                    "recommendation ranking failed; falling back to distance baseline type={}",
                    ex.getClass().getSimpleName());
            List<ParkingCandidate> fallback = DeterministicParkingCandidateRanker.baselineOrder(withBaseline)
                    .stream()
                    .map(c -> new ParkingCandidate(
                            c.id(),
                            c.channel(),
                            c.refId(),
                            c.title(),
                            c.latitude(),
                            c.longitude(),
                            c.distanceMeters(),
                            c.availability(),
                            c.sourceLabel(),
                            c.baselineOrder(),
                            c.reasons(),
                            null,
                            null,
                            RankingVersion.DISTANCE_BASELINE_V1.name()))
                    .toList();
            ParkingCandidateRanker.RankingOutcome outcome = new ParkingCandidateRanker.RankingOutcome(
                    fallback, RankingVersion.DISTANCE_BASELINE_V1, RankingStatus.FALLBACK);
            rankingMetrics.recordApplied(
                    outcome.version(), outcome.status(), System.nanoTime() - rankingStarted);
            return outcome;
        }
    }

    private void recordShadow(List<ParkingCandidate> baselineOrdered, List<ParkingCandidate> ranked) {
        if (baselineOrdered.isEmpty() || ranked.isEmpty()) {
            return;
        }
        boolean top1Changed = !baselineOrdered.getFirst().id().equals(ranked.getFirst().id());
        Set<String> baselineTop3 = new HashSet<>();
        Set<String> rankedTop3 = new HashSet<>();
        for (int i = 0; i < Math.min(3, baselineOrdered.size()); i++) {
            baselineTop3.add(baselineOrdered.get(i).id());
        }
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            rankedTop3.add(ranked.get(i).id());
        }
        int overlap = 0;
        for (String id : rankedTop3) {
            if (baselineTop3.contains(id)) {
                overlap++;
            }
        }
        rankingMetrics.recordShadow(top1Changed, overlap);
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
        return ChannelFetch.pending(future);
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
        return ChannelFetch.pending(future);
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

        static ChannelFetch pending(CompletableFuture<List<ParkingCandidate>> future) {
            return new ChannelFetch(null, future);
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
