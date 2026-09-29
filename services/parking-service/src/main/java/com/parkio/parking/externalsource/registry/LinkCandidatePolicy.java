package com.parkio.parking.externalsource.registry;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Conservative multi-family candidate policy. This policy never authorizes automatic linking. */
public final class LinkCandidatePolicy {
    public static final String ALGORITHM_VERSION = "registry-link-candidate-v1";
    public static final double CANDIDATE_RADIUS_METERS = 100.0;

    private LinkCandidatePolicy() {}

    public static LinkCandidateScore evaluate(LinkCandidateEvidence evidence) {
        Set<String> conflicts = hardConflicts(evidence);
        Set<String> signals = new LinkedHashSet<>();
        Map<String, Double> components = new LinkedHashMap<>();

        double distanceScore = evidence.distanceMeters() <= 25
                ? 0.30
                : evidence.distanceMeters() <= CANDIDATE_RADIUS_METERS ? 0.15 : 0;
        components.put("distance", distanceScore);
        if (distanceScore > 0) {
            signals.add("distance");
        }

        double nameScore = 0.30 * evidence.nameSimilarity();
        components.put("name", nameScore);
        if (evidence.nameSimilarity() >= 0.75) {
            signals.add("name");
        }

        double operatorScore = 0.15 * evidence.operatorSimilarity();
        components.put("operator", operatorScore);
        if (evidence.operatorSimilarity() >= 0.75) {
            signals.add("operator");
        }

        double typeScore = compatibleType(evidence.typeA(), evidence.typeB()) ? 0.10 : 0;
        components.put("type", typeScore);
        if (known(evidence.typeA()) && known(evidence.typeB()) && typeScore > 0) {
            signals.add("type");
        }

        double capacityScore = compatibleCapacity(evidence.capacityA(), evidence.capacityB()) ? 0.05 : 0;
        components.put("capacity", capacityScore);
        if (evidence.capacityA() != null && evidence.capacityB() != null && capacityScore > 0) {
            signals.add("capacity");
        }

        double addressScore = evidence.addressMatch() ? 0.05 : 0;
        double districtScore = evidence.districtMatch() ? 0.05 : 0;
        components.put("address", addressScore);
        components.put("district", districtScore);
        if (evidence.addressMatch()) {
            signals.add("address");
        }
        if (evidence.districtMatch()) {
            signals.add("district");
        }

        double total = components.values().stream().mapToDouble(Double::doubleValue).sum();
        boolean distanceOnly = signals.equals(Set.of("distance"));
        boolean nameOnly = signals.equals(Set.of("name"));
        boolean hasGeometry = signals.contains("distance");
        boolean hasSemantic = signals.stream().anyMatch(signal -> !"distance".equals(signal));
        boolean multiSignal = signals.size() >= 2 && hasGeometry && hasSemantic;
        boolean candidate = conflicts.isEmpty()
                && !distanceOnly
                && !nameOnly
                && multiSignal
                && evidence.distanceMeters() <= CANDIDATE_RADIUS_METERS
                && total >= 0.45;

        String reason = !conflicts.isEmpty()
                ? "hard_conflict"
                : distanceOnly
                        ? "distance_only"
                        : nameOnly
                                ? "name_only"
                                : !multiSignal ? "insufficient_signals" : candidate ? "multi_signal_candidate" : "below_threshold";
        return new LinkCandidateScore(candidate, Math.min(1, total), components, conflicts, signals, reason);
    }

    public static Set<String> hardConflicts(LinkCandidateEvidence evidence) {
        Set<String> conflicts = new LinkedHashSet<>();
        if (evidence.materialCoordinateConflict()) {
            conflicts.add("material_coordinate_conflict");
        }
        if (evidence.typeA() == MunicipalFacilityType.ON_STREET
                        && evidence.typeB() == MunicipalFacilityType.OFF_STREET
                || evidence.typeB() == MunicipalFacilityType.ON_STREET
                        && evidence.typeA() == MunicipalFacilityType.OFF_STREET) {
            conflicts.add("facility_type_exclusive");
        }
        if (evidence.zoneVsFacility()) {
            conflicts.add("zone_vs_facility");
        }
        if (evidence.operatorContradiction()) {
            conflicts.add("operator_contradiction");
        }
        if (restrictive(evidence.accessA())
                && restrictive(evidence.accessB())
                && evidence.accessA() != evidence.accessB()) {
            conflicts.add("access_exclusive");
        }
        if (evidence.addressConflict()) {
            conflicts.add("address_conflict");
        }
        if (evidence.districtConflict()) {
            conflicts.add("district_conflict");
        }
        if (!compatibleCapacity(evidence.capacityA(), evidence.capacityB())
                && evidence.capacityA() != null
                && evidence.capacityB() != null) {
            conflicts.add("capacity_divergence");
        }
        return conflicts;
    }

    private static boolean known(MunicipalFacilityType type) {
        return type != null && type != MunicipalFacilityType.UNKNOWN;
    }

    private static boolean compatibleType(MunicipalFacilityType a, MunicipalFacilityType b) {
        return !known(a) || !known(b) || a == b;
    }

    private static boolean compatibleCapacity(Integer a, Integer b) {
        if (a == null || b == null || a <= 0 || b <= 0) {
            return true;
        }
        return Math.min(a, b) / (double) Math.max(a, b) >= 0.4;
    }

    private static boolean restrictive(MunicipalAccessClassification access) {
        return access == MunicipalAccessClassification.PRIVATE
                || access == MunicipalAccessClassification.RESIDENTS
                || access == MunicipalAccessClassification.RESTRICTED;
    }
}