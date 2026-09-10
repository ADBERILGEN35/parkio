package com.parkio.parking.externalsource.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.LinkCandidatePairDiscoveryPort.DiscoveredPair;
import com.parkio.parking.application.port.LinkCandidatePairDiscoveryPort.SourceRecord;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.osm.ConflationPolicy;
import com.parkio.parking.externalsource.osm.OsmNameNormalizer;

public final class LinkCandidateEvidenceFactory {
    private final ObjectMapper objectMapper;

    public LinkCandidateEvidenceFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LinkCandidateEvidence create(DiscoveredPair pair) {
        SourceRecord a = pair.left();
        SourceRecord b = pair.right();
        if (!a.linkActive() || !b.linkActive()
                || a.rawRecordHash() == null || b.rawRecordHash() == null) {
            throw new IllegalArgumentException("active source records with versions are required");
        }
        double distance = Double.isFinite(pair.distanceMeters())
                ? pair.distanceMeters()
                : ConflationPolicy.haversineMeters(a.latitude(), a.longitude(), b.latitude(), b.longitude());
        double name = OsmNameNormalizer.similarity(a.name(), b.name());
        double operator = OsmNameNormalizer.similarity(a.operator(), b.operator());
        double address = OsmNameNormalizer.similarity(a.address(), b.address());
        boolean addressMatch = nonBlank(a.address()) && nonBlank(b.address())
                && (OsmNameNormalizer.normalize(a.address()).equals(OsmNameNormalizer.normalize(b.address()))
                        || address >= 0.85);
        String districtA = district(a.sourceMetadataJson());
        String districtB = district(b.sourceMetadataJson());
        boolean districtMatch = nonBlank(districtA) && nonBlank(districtB)
                && OsmNameNormalizer.normalize(districtA).equals(OsmNameNormalizer.normalize(districtB));
        boolean operatorContradiction = nonBlank(a.operator()) && nonBlank(b.operator()) && operator < 0.25;
        boolean addressConflict = nonBlank(a.address()) && nonBlank(b.address()) && address < 0.35;

        return new LinkCandidateEvidence(
                a.sourceKey(), a.externalId(), a.rawRecordHash(),
                b.sourceKey(), b.externalId(), b.rawRecordHash(),
                distance, name, operator,
                type(a.type()), type(b.type()), access(a.access()), access(b.access()),
                a.capacity(), b.capacity(), addressMatch, districtMatch,
                operatorContradiction, addressConflict, false, false);
    }

    private String district(String json) {
        if (!nonBlank(json)) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            for (String field : new String[] {"district", "ilce", "ilçe"}) {
                JsonNode value = root.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) return value.asText();
            }
        } catch (Exception ignored) {
            // Malformed optional metadata is not positive evidence.
        }
        return null;
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
