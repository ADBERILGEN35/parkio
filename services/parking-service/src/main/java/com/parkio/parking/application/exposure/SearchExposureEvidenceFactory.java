package com.parkio.parking.application.exposure;

import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.exposure.ExposureAvailabilityState;
import com.parkio.parking.exposure.ExposureCandidateId;
import com.parkio.parking.exposure.ExposureEvidence;
import com.parkio.parking.exposure.ExposurePublicationQuality;
import com.parkio.parking.exposure.ExposureQueryContext;
import com.parkio.parking.exposure.ExposureTrustLevel;
import com.parkio.parking.exposure.ExposureVehicleMatch;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Maps legacy search candidates to canonical exposure evidence without JPA or remote calls. */
public final class SearchExposureEvidenceFactory {

    private SearchExposureEvidenceFactory() {
    }

    public static List<ExposureEvidence> fromSearchResults(
            List<ParkingSpot> legacyOrder,
            double queryLatitude,
            double queryLongitude,
            double radiusMeters,
            Instant evaluatedAt) {
        Objects.requireNonNull(legacyOrder, "legacyOrder");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        int radius = boundedRadius(radiusMeters);
        List<ExposureEvidence> evidence = new ArrayList<>(legacyOrder.size());
        for (ParkingSpot spot : legacyOrder) {
            int distance = GeographyDistanceMeters.haversineMeters(
                    queryLatitude, queryLongitude, spot.latitude(), spot.longitude());
            evidence.add(single(spot, distance, radius, evaluatedAt));
        }
        return evidence;
    }

    public static ExposureQueryContext queryContext(
            double latitude,
            double longitude,
            double radiusMeters,
            int limit,
            boolean authenticated) {
        return new ExposureQueryContext(
                "NEARBY",
                radiusBand(radiusMeters),
                limitBand(limit),
                locationBand(latitude, longitude),
                authenticated);
    }

    private static ExposureEvidence single(
            ParkingSpot spot,
            int distanceMeters,
            int requestRadiusMeters,
            Instant evaluatedAt) {
        boolean visible = spot.isVisibleForSearch(evaluatedAt);
        ExposurePublicationQuality publicationQuality = publicationQuality(spot.status());
        ExposureAvailabilityState availability = availabilityState(spot, evaluatedAt, visible);
        Instant publishedAt = spot.activatedAt() != null ? spot.activatedAt() : spot.createdAt();
        return new ExposureEvidence(
                new ExposureCandidateId(spot.id()),
                distanceMeters,
                requestRadiusMeters,
                publicationQuality,
                availability,
                ExposureVehicleMatch.NOT_REQUESTED,
                ExposureTrustLevel.UNKNOWN,
                publishedAt,
                spot.activatedAt(),
                spot.expiresAt(),
                freshnessBand(publishedAt, spot.expiresAt(), evaluatedAt),
                distanceBand(distanceMeters, requestRadiusMeters),
                visible);
    }

    private static ExposurePublicationQuality publicationQuality(ParkingSpotStatus status) {
        if (status == ParkingSpotStatus.VERIFIED) {
            return ExposurePublicationQuality.VERIFIED;
        }
        if (status == ParkingSpotStatus.ACTIVE) {
            return ExposurePublicationQuality.ACTIVE;
        }
        return ExposurePublicationQuality.OTHER;
    }

    private static ExposureAvailabilityState availabilityState(
            ParkingSpot spot,
            Instant evaluatedAt,
            boolean visible) {
        if (!visible) {
            if (spot.legalStatus() == LegalStatus.ILLEGAL_OR_RISKY) {
                return ExposureAvailabilityState.UNAVAILABLE;
            }
            if (spot.expiresAt() != null && !spot.expiresAt().isAfter(evaluatedAt)) {
                return ExposureAvailabilityState.EXPIRED;
            }
            return ExposureAvailabilityState.UNAVAILABLE;
        }
        if (spot.expiresAt() == null) {
            return ExposureAvailabilityState.UNKNOWN;
        }
        Duration remaining = Duration.between(evaluatedAt, spot.expiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            return ExposureAvailabilityState.EXPIRED;
        }
        if (remaining.toMinutes() >= 8) {
            return ExposureAvailabilityState.AVAILABLE;
        }
        if (remaining.toMinutes() >= 4) {
            return ExposureAvailabilityState.LIKELY_AVAILABLE;
        }
        if (spot.status() == ParkingSpotStatus.VERIFIED && spot.verificationCount() > 0) {
            return ExposureAvailabilityState.LIKELY_AVAILABLE;
        }
        if (spot.filledReportCount() > 0) {
            return ExposureAvailabilityState.LIKELY_OCCUPIED;
        }
        return ExposureAvailabilityState.UNKNOWN;
    }

    static String freshnessBand(Instant publishedAt, Instant expiresAt, Instant evaluatedAt) {
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        long ageSeconds = Math.max(0, Duration.between(publishedAt, evaluatedAt).toSeconds());
        if (ageSeconds <= 120) {
            return "VERY_FRESH";
        }
        if (ageSeconds <= 300) {
            return "FRESH";
        }
        if (ageSeconds <= 600) {
            return "AGING";
        }
        if (expiresAt != null) {
            long remainingSeconds = Duration.between(evaluatedAt, expiresAt).toSeconds();
            if (remainingSeconds <= 120) {
                return "STALE";
            }
        }
        return "AGING";
    }

    static String distanceBand(int distanceMeters, int requestRadiusMeters) {
        int radius = Math.max(requestRadiusMeters, 1);
        int ratio = (int) ((long) distanceMeters * 10_000L / radius);
        if (ratio <= 2_500) {
            return "VERY_NEAR";
        }
        if (ratio <= 5_000) {
            return "NEAR";
        }
        if (ratio <= 7_500) {
            return "MID";
        }
        return "FAR";
    }

    static String radiusBand(double radiusMeters) {
        if (radiusMeters <= 500) {
            return "R0_500";
        }
        if (radiusMeters <= 1_500) {
            return "R500_1500";
        }
        if (radiusMeters <= 5_000) {
            return "R1500_5000";
        }
        return "R5000_PLUS";
    }

    static String limitBand(int limit) {
        if (limit <= 10) {
            return "L1_10";
        }
        if (limit <= 25) {
            return "L11_25";
        }
        return "L26_50";
    }

    static String locationBand(double latitude, double longitude) {
        long latBand = Math.round(latitude * 100.0);
        long lngBand = Math.round(longitude * 100.0);
        return latBand + ":" + lngBand;
    }

    public static boolean deterministicSample(ExposureQueryContext context, int samplePercent) {
        if (samplePercent >= 100) {
            return true;
        }
        if (samplePercent <= 0) {
            return false;
        }
        int hash = Math.floorMod(
                Objects.hash(context.searchType(), context.radiusBand(), context.limitBand(), context.locationBand()),
                100);
        return hash < samplePercent;
    }

    private static int boundedRadius(double radiusMeters) {
        if (radiusMeters <= 0) {
            throw new IllegalArgumentException("radiusMeters must be positive");
        }
        return (int) Math.min(Math.round(radiusMeters), Integer.MAX_VALUE);
    }
}
