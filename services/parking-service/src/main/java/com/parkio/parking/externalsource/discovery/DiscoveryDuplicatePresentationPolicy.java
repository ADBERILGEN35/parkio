package com.parkio.parking.externalsource.discovery;

import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.registry.LinkCandidatePolicy;
import com.parkio.parking.externalsource.registry.LinkCandidateScore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Query-time nearby duplicate-presentation policy (DATA-WP-07).
 *
 * <p>Matching reuses {@link LinkCandidatePolicy} hard-conflict and multi-signal rules.
 * Winner selection is a separate decision and never writes registry state.
 */
public final class DiscoveryDuplicatePresentationPolicy {
    public static final String POLICY_VERSION = "discovery-duplicate-presentation-v1";
    public static final String PAIR_IZUM_OSM = "IZUM_OSM";

    public enum Family {
        IZUM,
        OSM,
        OTHER
    }

    public enum Outcome {
        KEEP,
        SUPPRESS,
        SKIP_UNSUPPORTED_PAIR,
        DISTANCE_ONLY,
        NAME_ONLY,
        HARD_CONFLICT,
        INSUFFICIENT_SIGNALS,
        BELOW_THRESHOLD,
        OUTSIDE_RADIUS
    }

    public record Candidate(
            UUID id,
            Family family,
            String displayName,
            String operatorName,
            String addressText,
            double latitude,
            double longitude,
            Integer capacityTotal,
            com.parkio.parking.externalsource.MunicipalFacilityType facilityType,
            com.parkio.parking.externalsource.MunicipalAccessClassification access,
            MunicipalOccupancyFreshness freshness,
            Object payload) {}

    public record PairDecision(Outcome outcome, String reasonCategory, String sourceFamilyPair) {}

    public record ApplyResult<T>(
            List<T> kept,
            int considered,
            int strongDuplicates,
            int suppressed,
            int hardConflictVetoes,
            int distanceOnlyRejected,
            int nameOnlyRejected,
            boolean overfetchExhausted) {}

    private final double comparisonRadiusMeters;
    private final Set<String> supportedPairs;

    public DiscoveryDuplicatePresentationPolicy(double comparisonRadiusMeters) {
        this(comparisonRadiusMeters, supportedPairsDefault());
    }

    public DiscoveryDuplicatePresentationPolicy(double comparisonRadiusMeters, Set<String> supportedPairs) {
        if (!Double.isFinite(comparisonRadiusMeters) || comparisonRadiusMeters <= 0) {
            throw new IllegalArgumentException("comparisonRadiusMeters must be positive");
        }
        this.comparisonRadiusMeters = comparisonRadiusMeters;
        this.supportedPairs = supportedPairs == null || supportedPairs.isEmpty()
                ? supportedPairsDefault()
                : Set.copyOf(supportedPairs.stream()
                        .filter(Objects::nonNull)
                        .map(value -> value.trim().toUpperCase(Locale.ROOT))
                        .filter(value -> !value.isEmpty())
                        .toList());
    }

    public static Family familyOf(Set<String> linkedSourceKeys, String primarySourceKey) {
        Set<String> keys = MunicipalSourceIdentity.normalizeKeys(linkedSourceKeys);
        if (keys.isEmpty() && primarySourceKey != null && !primarySourceKey.isBlank()) {
            keys = Set.of(primarySourceKey.trim());
        }
        if (keys.stream().anyMatch(MunicipalSourceIdentity::isIzum)) {
            return Family.IZUM;
        }
        if (keys.stream().anyMatch(MunicipalSourceIdentity::isOsm)) {
            return Family.OSM;
        }
        return Family.OTHER;
    }

    public static boolean supportedPair(Family a, Family b) {
        return (a == Family.IZUM && b == Family.OSM) || (a == Family.OSM && b == Family.IZUM);
    }

    public PairDecision classify(Candidate left, Candidate right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        String pair = pairLabel(left, right);
        if (!supportedPair(left.family(), right.family()) || !this.supportedPairs.contains(pair)) {
            return new PairDecision(Outcome.SKIP_UNSUPPORTED_PAIR, "unsupported_pair", pair);
        }
        var evidence = DiscoveryPresentationEvidenceFactory.fromCandidates(left, right);
        if (evidence.distanceMeters() > comparisonRadiusMeters) {
            return new PairDecision(Outcome.OUTSIDE_RADIUS, "outside_radius", PAIR_IZUM_OSM);
        }
        LinkCandidateScore score = LinkCandidatePolicy.evaluate(evidence);
        if (!score.hardConflicts().isEmpty()) {
            return new PairDecision(Outcome.HARD_CONFLICT, "hard_conflict", PAIR_IZUM_OSM);
        }
        String reason = score.reasonCategory();
        if ("distance_only".equals(reason)) {
            return new PairDecision(Outcome.DISTANCE_ONLY, reason, PAIR_IZUM_OSM);
        }
        if ("name_only".equals(reason)) {
            return new PairDecision(Outcome.NAME_ONLY, reason, PAIR_IZUM_OSM);
        }
        if (score.candidate()) {
            return new PairDecision(Outcome.SUPPRESS, "presentation_duplicate", PAIR_IZUM_OSM);
        }
        if ("insufficient_signals".equals(reason)) {
            return new PairDecision(Outcome.INSUFFICIENT_SIGNALS, reason, PAIR_IZUM_OSM);
        }
        return new PairDecision(Outcome.BELOW_THRESHOLD, reason, PAIR_IZUM_OSM);
    }

    /**
     * Prefer publishable IZUM with LIVE/AGING occupancy, then STALE/missing IZUM, then OSM,
     * then stable facility-ID order.
     */
    public static Candidate selectWinner(Candidate a, Candidate b) {
        int rankA = winnerRank(a);
        int rankB = winnerRank(b);
        if (rankA != rankB) {
            return rankA < rankB ? a : b;
        }
        return a.id().compareTo(b.id()) <= 0 ? a : b;
    }

    private static int winnerRank(Candidate candidate) {
        if (candidate.family() == Family.IZUM) {
            MunicipalOccupancyFreshness freshness = candidate.freshness();
            if (freshness == MunicipalOccupancyFreshness.LIVE
                    || freshness == MunicipalOccupancyFreshness.AGING) {
                return 1;
            }
            return 2;
        }
        if (candidate.family() == Family.OSM) {
            return 3;
        }
        return 4;
    }

    public <T> ApplyResult<T> apply(List<Candidate> ordered, int requestedLimit) {
        Objects.requireNonNull(ordered, "ordered");
        if (requestedLimit <= 0) {
            throw new IllegalArgumentException("requestedLimit must be positive");
        }
        int strongDuplicates = 0;
        int suppressedCount = 0;
        int hardConflictVetoes = 0;
        int distanceOnlyRejected = 0;
        int nameOnlyRejected = 0;

        List<Candidate> kept = new ArrayList<>();
        Set<UUID> suppressed = new HashSet<>();

        for (Candidate candidate : ordered) {
            if (suppressed.contains(candidate.id())) {
                continue;
            }
            boolean dropped = false;
            for (int i = 0; i < kept.size(); i++) {
                Candidate existing = kept.get(i);
                PairDecision decision = classify(existing, candidate);
                switch (decision.outcome()) {
                    case HARD_CONFLICT -> hardConflictVetoes++;
                    case DISTANCE_ONLY -> distanceOnlyRejected++;
                    case NAME_ONLY -> nameOnlyRejected++;
                    case SUPPRESS -> {
                        strongDuplicates++;
                        Candidate winner = selectWinner(existing, candidate);
                        if (winner.id().equals(existing.id())) {
                            suppressed.add(candidate.id());
                            suppressedCount++;
                            dropped = true;
                        } else {
                            suppressed.add(existing.id());
                            suppressedCount++;
                            kept.set(i, candidate);
                            dropped = true;
                        }
                    }
                    default -> {
                        // keep both
                    }
                }
                if (dropped) {
                    break;
                }
            }
            if (!dropped && !suppressed.contains(candidate.id())) {
                kept.add(candidate);
            }
        }

        List<T> page = new ArrayList<>(Math.min(requestedLimit, kept.size()));
        for (Candidate candidate : kept) {
            if (page.size() >= requestedLimit) {
                break;
            }
            @SuppressWarnings("unchecked")
            T payload = (T) candidate.payload();
            page.add(payload);
        }
        boolean exhausted = kept.size() <= requestedLimit;
        return new ApplyResult<>(
                page,
                ordered.size(),
                strongDuplicates,
                suppressedCount,
                hardConflictVetoes,
                distanceOnlyRejected,
                nameOnlyRejected,
                exhausted && suppressedCount > 0 && page.size() < requestedLimit);
    }

    public static int boundedFetchLimit(int requestedLimit, int overfetchFactor, int absoluteMax) {
        if (requestedLimit <= 0 || overfetchFactor < 1 || absoluteMax < 1) {
            throw new IllegalArgumentException("invalid overfetch bounds");
        }
        long scaled = (long) requestedLimit * (long) overfetchFactor;
        return (int) Math.min(absoluteMax, Math.max(requestedLimit, scaled));
    }

    private static String pairLabel(Candidate a, Candidate b) {
        String left = a.family().name();
        String right = b.family().name();
        return left.compareTo(right) <= 0
                ? left + "_" + right
                : right + "_" + left;
    }

    public static Set<String> supportedPairsDefault() {
        return Set.copyOf(new LinkedHashSet<>(List.of(PAIR_IZUM_OSM)));
    }

    public static boolean allowlistContains(Set<String> allowlist, String pair) {
        if (allowlist == null || allowlist.isEmpty()) {
            return PAIR_IZUM_OSM.equals(pair);
        }
        return allowlist.stream()
                .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                .anyMatch(pair::equals);
    }

    public static Comparator<Candidate> stableOrder() {
        return Comparator.comparing(Candidate::id);
    }
}
