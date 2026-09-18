package com.parkio.parking.application.recommendation.ranking;

import com.parkio.parking.application.recommendation.CandidateAvailability;
import com.parkio.parking.application.recommendation.ParkingCandidate;
import com.parkio.parking.application.recommendation.ParkingCandidateChannel;
import com.parkio.parking.application.recommendation.RecommendationReason;
import com.parkio.parking.application.recommendation.RecommendationReasonCode;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic weighted ranker (WP-SPA-06). Pure scoring after favourite IDs
 * are supplied by orchestration.
 */
@Component
public class DeterministicParkingCandidateRanker implements ParkingCandidateRanker {

    static final double CLOSE_DISTANCE_REASON_THRESHOLD = 0.55;
    static final double HIGH_CAPACITY_REASON_THRESHOLD = 0.55;
    static final double HIGH_CONFIDENCE_REASON_THRESHOLD = 0.75;
    static final int MAX_REASONS = 3;

    private final Clock clock;

    public DeterministicParkingCandidateRanker(Clock clock) {
        this.clock = clock;
    }

    @Override
    public RankingOutcome rank(RankingContext context) {
        RankingProperties.RankingConfiguration config = context.config();
        if (!config.enabled()) {
            List<ParkingCandidate> baseline = baselineOrder(context.candidates()).stream()
                    .map(c -> enrich(c, null, null, RankingVersion.DISTANCE_BASELINE_V1, List.of()))
                    .toList();
            return new RankingOutcome(baseline, RankingVersion.DISTANCE_BASELINE_V1, RankingStatus.DISABLED);
        }

        List<Scored> scored = new ArrayList<>(context.candidates().size());
        for (ParkingCandidate candidate : context.candidates()) {
            CandidateScoreBreakdown breakdown = scoreFactors(candidate, context.favouriteFacilityIds(), config);
            double total = weightedTotal(breakdown, config);
            List<RecommendationReason> reasons = selectReasons(candidate, breakdown, context.favouriteFacilityIds());
            scored.add(new Scored(candidate, total, breakdown, reasons));
        }

        scored.sort(RANKED_ORDER);
        List<ParkingCandidate> ranked = scored.stream()
                .map(s -> enrich(
                        s.candidate(),
                        roundScore(s.total()),
                        s.breakdown(),
                        RankingVersion.DETERMINISTIC_V1,
                        s.reasons()))
                .toList();
        return new RankingOutcome(ranked, RankingVersion.DETERMINISTIC_V1, RankingStatus.APPLIED);
    }

    CandidateScoreBreakdown scoreFactors(
            ParkingCandidate candidate,
            Set<UUID> favouriteFacilityIds,
            RankingProperties.RankingConfiguration config) {
        return new CandidateScoreBreakdown(
                distanceScore(candidate.distanceMeters(), config.distanceCapMeters()),
                freshnessScore(candidate, clock.instant()),
                capacityScore(candidate),
                confidenceScore(candidate, clock.instant()),
                favouriteScore(candidate, favouriteFacilityIds));
    }

    static double distanceScore(int distanceMeters, int capMeters) {
        if (distanceMeters <= 0) {
            return 1.0;
        }
        if (capMeters <= 0) {
            return 0.0;
        }
        return 1.0 - Math.min((double) distanceMeters / (double) capMeters, 1.0);
    }

    static double freshnessScore(ParkingCandidate candidate, Instant now) {
        CandidateAvailability availability = candidate.availability();
        if (availability == null) {
            return 0.0;
        }
        if (availability.kind() == CandidateAvailability.Kind.MUNICIPAL) {
            MunicipalOccupancyFreshness freshness = availability.freshness();
            if (freshness == null) {
                return 0.0;
            }
            return switch (freshness) {
                case LIVE -> 1.0;
                case AGING -> 0.7;
                case STALE -> 0.2;
                case UNAVAILABLE, INVALID -> 0.0;
            };
        }
        return communityFreshness(availability, now);
    }

    static double capacityScore(ParkingCandidate candidate) {
        CandidateAvailability availability = candidate.availability();
        if (availability == null || availability.kind() != CandidateAvailability.Kind.MUNICIPAL) {
            return 0.0;
        }
        MunicipalOccupancyFreshness freshness = availability.freshness();
        if (freshness != MunicipalOccupancyFreshness.LIVE
                && freshness != MunicipalOccupancyFreshness.AGING) {
            // Static / stale / unknown: do not invent capacity advantage.
            return 0.0;
        }
        Integer available = availability.availableSpaces();
        Integer capacity = availability.capacityTotal();
        if (available == null || capacity == null || capacity <= 0) {
            return 0.0;
        }
        double ratio = Math.min(Math.max(available.doubleValue() / capacity.doubleValue(), 0.0), 1.0);
        // Mild absolute free-space boost for large lots with many free spaces.
        double absolute = Math.min(available.doubleValue() / 50.0, 1.0);
        return Math.min(0.7 * ratio + 0.3 * absolute, 1.0);
    }

    static double confidenceScore(ParkingCandidate candidate, Instant now) {
        CandidateAvailability availability = candidate.availability();
        if (availability == null) {
            return 0.0;
        }
        if (availability.kind() == CandidateAvailability.Kind.MUNICIPAL) {
            MunicipalOccupancyFreshness freshness = availability.freshness();
            if (freshness == MunicipalOccupancyFreshness.LIVE) {
                return 1.0;
            }
            if (freshness == MunicipalOccupancyFreshness.AGING) {
                return 0.75;
            }
            if (freshness == MunicipalOccupancyFreshness.STALE) {
                return 0.3;
            }
            // OSM / static mapped facility — known inventory, no live occupancy.
            return 0.4;
        }
        double freshness = communityFreshness(availability, now);
        String status = availability.communityStatus();
        if (status != null && status.equalsIgnoreCase("VERIFIED")) {
            return Math.min(0.55, Math.max(freshness, 0.45));
        }
        if (status != null && status.equalsIgnoreCase("ACTIVE")) {
            return Math.min(0.45, Math.max(freshness, 0.35));
        }
        return Math.min(0.3, freshness);
    }

    static double favouriteScore(ParkingCandidate candidate, Set<UUID> favouriteFacilityIds) {
        if (candidate.channel() != ParkingCandidateChannel.MUNICIPAL_FACILITY) {
            return 0.0;
        }
        if (favouriteFacilityIds == null || favouriteFacilityIds.isEmpty()) {
            return 0.0;
        }
        try {
            UUID id = UUID.fromString(candidate.refId());
            return favouriteFacilityIds.contains(id) ? 1.0 : 0.0;
        } catch (IllegalArgumentException ex) {
            return 0.0;
        }
    }

    static double weightedTotal(
            CandidateScoreBreakdown breakdown, RankingProperties.RankingConfiguration config) {
        double total = config.distanceWeight() * breakdown.distance()
                + config.freshnessWeight() * breakdown.freshness()
                + config.capacityWeight() * breakdown.capacity()
                + config.confidenceWeight() * breakdown.confidence()
                + config.favouriteWeight() * breakdown.favourite();
        if (!Double.isFinite(total) || total < 0.0) {
            return 0.0;
        }
        return Math.min(total, 1.0);
    }

    static List<RecommendationReason> selectReasons(
            ParkingCandidate candidate,
            CandidateScoreBreakdown breakdown,
            Set<UUID> favouriteFacilityIds) {
        List<RecommendationReason> selected = new ArrayList<>(MAX_REASONS);
        boolean isFavourite = favouriteScore(candidate, favouriteFacilityIds) >= 1.0;
        if (isFavourite) {
            selected.add(RecommendationReason.of(RecommendationReasonCode.FAVOURITE));
        }
        CandidateAvailability availability = candidate.availability();
        if (availability != null
                && availability.kind() == CandidateAvailability.Kind.MUNICIPAL
                && availability.freshness() == MunicipalOccupancyFreshness.LIVE
                && availability.availableSpaces() != null) {
            addUnique(selected, RecommendationReasonCode.LIVE_AVAILABILITY);
        }
        if (breakdown.distance() >= CLOSE_DISTANCE_REASON_THRESHOLD) {
            addUnique(selected, RecommendationReasonCode.CLOSE_TO_DESTINATION);
        }
        if (breakdown.capacity() >= HIGH_CAPACITY_REASON_THRESHOLD
                || (availability != null
                        && availability.capacityTotal() != null
                        && availability.capacityTotal() >= ParkingCandidate.HIGH_CAPACITY_THRESHOLD
                        && availability.availableSpaces() != null
                        && availability.availableSpaces() > 0)) {
            addUnique(selected, RecommendationReasonCode.HIGH_CAPACITY);
        }
        if (breakdown.confidence() >= HIGH_CONFIDENCE_REASON_THRESHOLD) {
            addUnique(selected, RecommendationReasonCode.HIGH_CONFIDENCE);
        }
        if (candidate.channel() == ParkingCandidateChannel.COMMUNITY_SPOT) {
            addUnique(selected, RecommendationReasonCode.COMMUNITY_FRESH);
        }
        if (availability != null
                && availability.kind() == CandidateAvailability.Kind.MUNICIPAL
                && (availability.freshness() == MunicipalOccupancyFreshness.UNAVAILABLE
                        || availability.freshness() == MunicipalOccupancyFreshness.STALE)) {
            addUnique(selected, RecommendationReasonCode.STATIC_INVENTORY);
        }
        if (selected.size() > MAX_REASONS) {
            return List.copyOf(selected.subList(0, MAX_REASONS));
        }
        return List.copyOf(selected);
    }

    private static void addUnique(List<RecommendationReason> reasons, RecommendationReasonCode code) {
        if (reasons.size() >= MAX_REASONS) {
            return;
        }
        for (RecommendationReason reason : reasons) {
            if (reason.code() == code) {
                return;
            }
        }
        reasons.add(RecommendationReason.of(code));
    }

    private static double communityFreshness(CandidateAvailability availability, Instant now) {
        Instant expiresAt = availability.expiresAt();
        if (expiresAt == null) {
            return 0.4;
        }
        if (expiresAt.isBefore(now)) {
            return 0.0;
        }
        return 0.65;
    }

    public static List<ParkingCandidate> baselineOrder(List<ParkingCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingInt(ParkingCandidate::distanceMeters)
                        .thenComparing(c -> c.channel().name())
                        .thenComparing(ParkingCandidate::refId))
                .toList();
    }

    private static final Comparator<Scored> RANKED_ORDER =
            Comparator.comparingDouble(Scored::total).reversed()
                    .thenComparingInt(s -> s.candidate().distanceMeters())
                    .thenComparing(s -> s.candidate().channel().name())
                    .thenComparing(s -> s.candidate().refId());

    private static ParkingCandidate enrich(
            ParkingCandidate candidate,
            Double score,
            CandidateScoreBreakdown breakdown,
            RankingVersion version,
            List<RecommendationReason> reasons) {
        List<RecommendationReason> finalReasons = reasons == null || reasons.isEmpty()
                ? candidate.reasons()
                : reasons;
        return new ParkingCandidate(
                candidate.id(),
                candidate.channel(),
                candidate.refId(),
                candidate.title(),
                candidate.latitude(),
                candidate.longitude(),
                candidate.distanceMeters(),
                candidate.availability(),
                candidate.sourceLabel(),
                candidate.baselineOrder(),
                finalReasons,
                score,
                breakdown,
                version.name());
    }

    private static double roundScore(double score) {
        return Math.round(score * 10_000.0) / 10_000.0;
    }

    private record Scored(
            ParkingCandidate candidate,
            double total,
            CandidateScoreBreakdown breakdown,
            List<RecommendationReason> reasons) {}
}
