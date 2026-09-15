package com.parkio.parking.externalsource.registry;

import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.MunicipalSourcePublicationPolicy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DATA-WP-09: bounds public facility provenance to allow-listed fields and
 * publishable source keys. Answers only "which public source supplied this field?"
 * Never exposes confidence, review state, link state, or registry internals.
 */
public final class PublicProvenancePublicationPolicy {
    public static final String POLICY_VERSION = "public-provenance-v1";

    /**
     * Fields that map to already-public facility DTO attributes.
     * Other registry provenance fields stay unpublished even when stored.
     */
    public static final Set<String> PUBLIC_FIELD_ALLOWLIST = Set.of(
            RegistryField.NAME.name(),
            RegistryField.COORDINATES.name(),
            RegistryField.ADDRESS.name(),
            RegistryField.OPERATOR.name(),
            RegistryField.FACILITY_TYPE.name(),
            RegistryField.STATIC_CAPACITY.name(),
            RegistryField.ATTRIBUTION.name());

    public record FieldSource(String fieldName, String sourceKey) {
        public FieldSource {
            fieldName = requireText(fieldName, "fieldName");
            sourceKey = requireText(sourceKey, "sourceKey");
        }
    }

    public record BoundedProvenance(
            List<String> contributingSourceKeys,
            Map<String, String> selectedFieldProvenanceSummary) {
        public static BoundedProvenance empty() {
            return new BoundedProvenance(List.of(), Map.of());
        }

        public boolean isEmpty() {
            return contributingSourceKeys.isEmpty() && selectedFieldProvenanceSummary.isEmpty();
        }
    }

    private final MunicipalSourcePublicationPolicy publicationPolicy;

    public PublicProvenancePublicationPolicy(MunicipalSourcePublicationPolicy publicationPolicy) {
        this.publicationPolicy = Objects.requireNonNull(publicationPolicy, "publicationPolicy");
    }

    public BoundedProvenance project(List<FieldSource> rows) {
        if (rows == null || rows.isEmpty()) {
            return BoundedProvenance.empty();
        }
        Map<String, String> summary = new LinkedHashMap<>();
        Set<String> sources = new LinkedHashSet<>();
        for (FieldSource row : rows) {
            if (!PUBLIC_FIELD_ALLOWLIST.contains(row.fieldName())) {
                continue;
            }
            if (!publicationPolicy.isSourceLinkPublishable(row.sourceKey())) {
                continue;
            }
            // First row wins; SQL callers should ORDER BY field_name for determinism.
            if (summary.containsKey(row.fieldName())) {
                continue;
            }
            summary.put(row.fieldName(), row.sourceKey());
            sources.add(row.sourceKey());
        }
        return new BoundedProvenance(List.copyOf(sources), Map.copyOf(summary));
    }

    public static String sourceFamilyLabel(List<String> contributingSourceKeys) {
        Set<String> keys = MunicipalSourceIdentity.normalizeKeys(contributingSourceKeys);
        if (keys.isEmpty()) {
            return "none";
        }
        boolean izum = keys.stream().anyMatch(MunicipalSourceIdentity::isIzum);
        boolean ispark = keys.stream().anyMatch(MunicipalSourceIdentity::isIspark);
        boolean anpark = keys.stream().anyMatch(MunicipalSourceIdentity::isAnpark);
        boolean konya = keys.stream().anyMatch(MunicipalSourceIdentity::isKonya);
        boolean kayseri = keys.stream().anyMatch(MunicipalSourceIdentity::isKayseri);
        boolean osm = keys.stream().anyMatch(MunicipalSourceIdentity::isOsm);
        if (izum && osm) {
            return "mixed";
        }
        if (ispark && osm) {
            return "mixed";
        }
        if (anpark && osm) {
            return "mixed";
        }
        if (konya && osm) {
            return "mixed";
        }
        if (kayseri && osm) {
            return "mixed";
        }
        if (izum) {
            return MunicipalSourceIdentity.FAMILY_IZUM;
        }
        if (ispark) {
            return MunicipalSourceIdentity.FAMILY_ISPARK;
        }
        if (anpark) {
            return MunicipalSourceIdentity.FAMILY_ANPARK;
        }
        if (konya) {
            return MunicipalSourceIdentity.FAMILY_KONYA;
        }
        if (kayseri) {
            return MunicipalSourceIdentity.FAMILY_KAYSERI;
        }
        if (osm) {
            return MunicipalSourceIdentity.FAMILY_OSM;
        }
        return MunicipalSourceIdentity.FAMILY_UNKNOWN;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
