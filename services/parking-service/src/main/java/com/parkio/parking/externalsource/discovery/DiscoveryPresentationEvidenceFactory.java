package com.parkio.parking.externalsource.discovery;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.osm.ConflationPolicy;
import com.parkio.parking.externalsource.osm.OsmNameNormalizer;
import com.parkio.parking.externalsource.registry.LinkCandidateEvidence;

/** Builds DATA-WP-04-compatible evidence for query-time presentation matching only. */
public final class DiscoveryPresentationEvidenceFactory {
    private DiscoveryPresentationEvidenceFactory() {}

    public static LinkCandidateEvidence fromCandidates(
            DiscoveryDuplicatePresentationPolicy.Candidate left,
            DiscoveryDuplicatePresentationPolicy.Candidate right) {
        String keyA = sourceKey(left.family());
        String keyB = sourceKey(right.family());
        double distance = ConflationPolicy.haversineMeters(
                left.latitude(), left.longitude(), right.latitude(), right.longitude());
        double name = OsmNameNormalizer.similarity(left.displayName(), right.displayName());
        double operator = OsmNameNormalizer.similarity(left.operatorName(), right.operatorName());
        double address = OsmNameNormalizer.similarity(left.addressText(), right.addressText());
        boolean addressMatch = nonBlank(left.addressText()) && nonBlank(right.addressText())
                && (OsmNameNormalizer.normalize(left.addressText())
                                .equals(OsmNameNormalizer.normalize(right.addressText()))
                        || address >= 0.85);
        boolean districtMatch = districtTokensMatch(left.addressText(), right.addressText());
        boolean operatorContradiction = nonBlank(left.operatorName())
                && nonBlank(right.operatorName())
                && operator < 0.25;
        boolean addressConflict = nonBlank(left.addressText())
                && nonBlank(right.addressText())
                && address < 0.35;
        boolean districtConflict = districtTokensConflict(left.addressText(), right.addressText());

        return new LinkCandidateEvidence(
                keyA,
                left.id().toString(),
                left.id().toString(),
                keyB,
                right.id().toString(),
                right.id().toString(),
                distance,
                name,
                operator,
                type(left.facilityType()),
                type(right.facilityType()),
                access(left.access()),
                access(right.access()),
                left.capacityTotal(),
                right.capacityTotal(),
                addressMatch,
                districtMatch,
                operatorContradiction,
                addressConflict,
                districtConflict,
                false);
    }

    /**
     * Lightweight district heuristic from address text when structured district metadata is absent.
     * Known İzmir district tokens only; absence never creates positive match evidence.
     */
    private static boolean districtTokensMatch(String addressA, String addressB) {
        String a = districtToken(addressA);
        String b = districtToken(addressB);
        return a != null && a.equals(b);
    }

    private static boolean districtTokensConflict(String addressA, String addressB) {
        String a = districtToken(addressA);
        String b = districtToken(addressB);
        return a != null && b != null && !a.equals(b);
    }

    private static String districtToken(String address) {
        if (!nonBlank(address)) {
            return null;
        }
        String normalized = OsmNameNormalizer.normalize(address);
        String[] districts = {
            "bornova", "karsiyaka", "konak", "buca", "cigli", "bayrakli", "gaziemir",
            "balcova", "narlidere", "guzelbahce", "torbali", "menderes", "seferihisar",
            "urla", "cesme", "karabaglar", "aliaga", "foca", "kemalpasa", "bergama"
        };
        for (String district : districts) {
            if (normalized.equals(district)
                    || normalized.startsWith(district + " ")
                    || normalized.endsWith(" " + district)
                    || normalized.contains(" " + district + " ")) {
                return district;
            }
        }
        return null;
    }

    private static String sourceKey(DiscoveryDuplicatePresentationPolicy.Family family) {
        return switch (family) {
            case IZUM -> MunicipalSourceIdentity.IZUM;
            case OSM -> MunicipalSourceIdentity.OSM;
            case OTHER -> "unsupported-other";
        };
    }

    private static MunicipalFacilityType type(MunicipalFacilityType value) {
        return value == null ? MunicipalFacilityType.UNKNOWN : value;
    }

    private static MunicipalAccessClassification access(MunicipalAccessClassification value) {
        return value == null ? MunicipalAccessClassification.UNKNOWN : value;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
