package com.parkio.parking.externalsource.osm;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized conservative conflation thresholds. False merges are worse than duplicates.
 */
public final class ConflationPolicy {
    public static final String POLICY_VERSION = "osm-conflation-v1";

    /** Max centroid distance (meters) for candidate generation. */
    public static final double CANDIDATE_RADIUS_METERS = 75.0;
    /** Max distance for auto-match geometry gate. */
    public static final double AUTO_MATCH_MAX_DISTANCE_METERS = 35.0;
    /** Strong name similarity threshold. */
    public static final double STRONG_NAME_SIMILARITY = 0.88;
    /** Minimum total score for auto-match (when other gates pass). */
    public static final double AUTO_MATCH_MIN_SCORE = 0.78;
    /** Review band lower bound. */
    public static final double REVIEW_MIN_SCORE = 0.45;

    private ConflationPolicy() {}

    public record Candidate(
            String externalIdA,
            String externalIdB,
            double distanceMeters,
            double nameSimilarity,
            double operatorSimilarity,
            boolean typeCompatible,
            boolean accessCompatible,
            boolean capacityCompatible,
            boolean hardConflict,
            String hardConflictReason,
            double score) {}

    public static Candidate evaluate(
            String externalA,
            String nameA,
            String operatorA,
            MunicipalFacilityType typeA,
            MunicipalAccessClassification accessA,
            Integer capacityA,
            double latA,
            double lngA,
            String externalB,
            String nameB,
            String operatorB,
            MunicipalFacilityType typeB,
            MunicipalAccessClassification accessB,
            Integer capacityB,
            double latB,
            double lngB) {
        double distance = haversineMeters(latA, lngA, latB, lngB);
        double nameSim = OsmNameNormalizer.similarity(nameA, nameB);
        double opSim = OsmNameNormalizer.similarity(operatorA, operatorB);
        boolean typeOk = typeCompatible(typeA, typeB);
        boolean accessOk = accessCompatible(accessA, accessB);
        boolean capacityOk = capacityCompatible(capacityA, capacityB);

        String conflict = hardConflict(typeA, typeB, accessA, accessB, nameSim, distance, nameA, nameB);
        boolean hard = conflict != null;

        double score = 0.0;
        if (distance <= AUTO_MATCH_MAX_DISTANCE_METERS) {
            score += 0.35;
        } else if (distance <= CANDIDATE_RADIUS_METERS) {
            score += 0.15;
        }
        score += 0.35 * nameSim;
        score += 0.15 * opSim;
        if (typeOk) {
            score += 0.10;
        }
        if (capacityOk) {
            score += 0.05;
        }

        return new Candidate(externalA, externalB, distance, nameSim, opSim, typeOk, accessOk,
                capacityOk, hard, conflict, Math.min(1.0, score));
    }

    public static ConflationDecision decide(Candidate candidate, boolean unnamedOsm) {
        if (candidate.hardConflict()) {
            return ConflationDecision.REJECTED;
        }
        // Distance-only / unnamed nearby must not auto-match.
        if (unnamedOsm && candidate.nameSimilarity() < STRONG_NAME_SIMILARITY) {
            if (candidate.score() >= REVIEW_MIN_SCORE) {
                return ConflationDecision.REVIEW_REQUIRED;
            }
            return ConflationDecision.NOT_MATCHED;
        }
        boolean geometryGate = candidate.distanceMeters() <= AUTO_MATCH_MAX_DISTANCE_METERS;
        boolean strongSemantic = candidate.nameSimilarity() >= STRONG_NAME_SIMILARITY
                || (candidate.operatorSimilarity() >= STRONG_NAME_SIMILARITY && candidate.typeCompatible());
        if (geometryGate && strongSemantic && candidate.score() >= AUTO_MATCH_MIN_SCORE
                && candidate.accessCompatible() && candidate.typeCompatible()) {
            return ConflationDecision.AUTO_MATCHED;
        }
        if (candidate.distanceMeters() <= CANDIDATE_RADIUS_METERS
                && candidate.score() >= REVIEW_MIN_SCORE) {
            return ConflationDecision.REVIEW_REQUIRED;
        }
        return ConflationDecision.NOT_MATCHED;
    }

    public static Map<String, Object> signals(Candidate candidate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("distanceMeters", candidate.distanceMeters());
        map.put("nameSimilarity", candidate.nameSimilarity());
        map.put("operatorSimilarity", candidate.operatorSimilarity());
        map.put("typeCompatible", candidate.typeCompatible());
        map.put("accessCompatible", candidate.accessCompatible());
        map.put("capacityCompatible", candidate.capacityCompatible());
        map.put("hardConflict", candidate.hardConflict());
        map.put("hardConflictReason", candidate.hardConflictReason());
        map.put("score", candidate.score());
        return map;
    }

    private static String hardConflict(
            MunicipalFacilityType typeA,
            MunicipalFacilityType typeB,
            MunicipalAccessClassification accessA,
            MunicipalAccessClassification accessB,
            double nameSim,
            double distance,
            String nameA,
            String nameB) {
        if (typeA == MunicipalFacilityType.ON_STREET && typeB == MunicipalFacilityType.OFF_STREET
                || typeB == MunicipalFacilityType.ON_STREET && typeA == MunicipalFacilityType.OFF_STREET) {
            return "facility_type_exclusive";
        }
        if (!accessCompatible(accessA, accessB)
                && accessA != MunicipalAccessClassification.UNKNOWN
                && accessB != MunicipalAccessClassification.UNKNOWN) {
            return "access_exclusive";
        }
        if (distance > 15 && nameSim < 0.4
                && nameA != null && !nameA.isBlank()
                && nameB != null && !nameB.isBlank()) {
            // Distinct high-confidence names at non-trivial distance.
            if (OsmNameNormalizer.normalize(nameA).length() >= 4
                    && OsmNameNormalizer.normalize(nameB).length() >= 4
                    && nameSim < 0.35) {
                return "distinct_names";
            }
        }
        return null;
    }

    private static boolean typeCompatible(MunicipalFacilityType a, MunicipalFacilityType b) {
        if (a == MunicipalFacilityType.UNKNOWN || b == MunicipalFacilityType.UNKNOWN) {
            return true;
        }
        return a == b;
    }

    private static boolean accessCompatible(MunicipalAccessClassification a, MunicipalAccessClassification b) {
        if (a == MunicipalAccessClassification.UNKNOWN || b == MunicipalAccessClassification.UNKNOWN) {
            return true;
        }
        if (a == MunicipalAccessClassification.PRIVATE || a == MunicipalAccessClassification.RESIDENTS
                || a == MunicipalAccessClassification.RESTRICTED) {
            return a == b;
        }
        if (b == MunicipalAccessClassification.PRIVATE || b == MunicipalAccessClassification.RESIDENTS
                || b == MunicipalAccessClassification.RESTRICTED) {
            return a == b;
        }
        return true;
    }

    private static boolean capacityCompatible(Integer a, Integer b) {
        if (a == null || b == null || a <= 0 || b <= 0) {
            return true;
        }
        double ratio = Math.min(a, b) / (double) Math.max(a, b);
        return ratio >= 0.4;
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}