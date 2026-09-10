package com.parkio.parking.externalsource.registry;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import java.util.Objects;

public record LinkCandidateEvidence(
        String sourceKeyA,
        String externalIdA,
        String sourceVersionA,
        String sourceKeyB,
        String externalIdB,
        String sourceVersionB,
        double distanceMeters,
        double nameSimilarity,
        double operatorSimilarity,
        MunicipalFacilityType typeA,
        MunicipalFacilityType typeB,
        MunicipalAccessClassification accessA,
        MunicipalAccessClassification accessB,
        Integer capacityA,
        Integer capacityB,
        boolean addressMatch,
        boolean districtMatch,
        boolean operatorContradiction,
        boolean addressConflict,
        boolean districtConflict,
        boolean zoneVsFacility) {

    public static final double MATERIAL_COORDINATE_CONFLICT_METERS = 150.0;

    public LinkCandidateEvidence {
        Objects.requireNonNull(sourceKeyA, "sourceKeyA");
        Objects.requireNonNull(externalIdA, "externalIdA");
        Objects.requireNonNull(sourceVersionA, "sourceVersionA");
        Objects.requireNonNull(sourceKeyB, "sourceKeyB");
        Objects.requireNonNull(externalIdB, "externalIdB");
        Objects.requireNonNull(sourceVersionB, "sourceVersionB");
        if (sourceKeyA.equals(sourceKeyB)) {
            throw new IllegalArgumentException("candidate sources must differ");
        }
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0) {
            throw new IllegalArgumentException("distanceMeters must be finite and non-negative");
        }
        if (!betweenZeroAndOne(nameSimilarity) || !betweenZeroAndOne(operatorSimilarity)) {
            throw new IllegalArgumentException("similarities must be between zero and one");
        }
    }

    public LinkCandidateEvidence(
            String sourceKeyA,
            String externalIdA,
            String sourceVersionA,
            String sourceKeyB,
            String externalIdB,
            String sourceVersionB,
            double distanceMeters,
            double nameSimilarity,
            double operatorSimilarity,
            MunicipalFacilityType typeA,
            MunicipalFacilityType typeB,
            MunicipalAccessClassification accessA,
            MunicipalAccessClassification accessB,
            Integer capacityA,
            Integer capacityB,
            boolean addressMatch,
            boolean districtMatch) {
        this(
                sourceKeyA, externalIdA, sourceVersionA,
                sourceKeyB, externalIdB, sourceVersionB,
                distanceMeters, nameSimilarity, operatorSimilarity,
                typeA, typeB, accessA, accessB, capacityA, capacityB,
                addressMatch, districtMatch, false, false, false, false);
    }

    public String sourceFamilyPair() {
        String a = CanonicalFieldPrecedencePolicy.sourceFamily(sourceKeyA);
        String b = CanonicalFieldPrecedencePolicy.sourceFamily(sourceKeyB);
        return a.compareTo(b) <= 0 ? a + "_" + b : b + "_" + a;
    }

    public boolean materialCoordinateConflict() {
        return distanceMeters > MATERIAL_COORDINATE_CONFLICT_METERS && nameSimilarity >= 0.85;
    }

    private static boolean betweenZeroAndOne(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}