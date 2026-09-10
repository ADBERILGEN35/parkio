package com.parkio.parking.externalsource.registry;

import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * DATA-WP-10: decides which allow-listed public fields an ingest path actually
 * supplied. Does not guess multi-source ownership or invent absent values.
 */
public final class IngestFieldProvenancePolicy {
    public static final String POLICY_VERSION = "ingest-provenance-v1";
    public static final String REASON_IZUM_SYNC = "ingest_izum_sync";
    public static final String REASON_OSM_IMPORT = "ingest_osm_import";
    public static final String REASON_LIVE_ADAPTER_SYNC = "ingest_live_adapter_sync";
    public static final String REASON_FAKE_TEST_SYNC = "ingest_fake_test_sync";
    public static final String CONFIDENCE_SELECTED = "SELECTED";

    public static final Set<String> INGEST_FIELD_ALLOWLIST =
            PublicProvenancePublicationPolicy.PUBLIC_FIELD_ALLOWLIST;

    public record SuppliedField(RegistryField field) {
        public SuppliedField {
            Objects.requireNonNull(field, "field");
            if (!INGEST_FIELD_ALLOWLIST.contains(field.name())) {
                throw new IllegalArgumentException("field not allow-listed for ingest provenance: " + field);
            }
        }
    }

    /**
     * Fields İZUM sync wrote onto the facility row (availability is separate).
     */
    public static List<SuppliedField> forIzumFacility(NormalizedMunicipalFacility facility) {
        return forLiveInventoryFacility(facility);
    }

    /** Shared field set for live inventory adapters (İZUM, FAKE_TEST, future HTTP feeds). */
    public static List<SuppliedField> forLiveInventoryFacility(NormalizedMunicipalFacility facility) {
        Objects.requireNonNull(facility, "facility");
        List<SuppliedField> fields = new ArrayList<>();
        if (hasText(facility.displayName())) {
            fields.add(new SuppliedField(RegistryField.NAME));
        }
        fields.add(new SuppliedField(RegistryField.COORDINATES));
        if (hasText(facility.addressText())) {
            fields.add(new SuppliedField(RegistryField.ADDRESS));
        }
        if (hasText(facility.operatorName())) {
            fields.add(new SuppliedField(RegistryField.OPERATOR));
        }
        if (facility.facilityType() != null) {
            fields.add(new SuppliedField(RegistryField.FACILITY_TYPE));
        }
        if (facility.capacityTotal() != null) {
            fields.add(new SuppliedField(RegistryField.STATIC_CAPACITY));
        }
        fields.add(new SuppliedField(RegistryField.ATTRIBUTION));
        return List.copyOf(fields);
    }

    /**
     * Fields OSM import supplied. NAME provenance is claimed only when the selected
     * display label came from a real OSM name-bearing tag ({@code name:tr}, {@code name},
     * {@code official_name}, {@code short_name}) — never for operator/brand/type/neutral
     * fallbacks (DATA-WP-13). ADDRESS is never supplied by OSM import.
     */
    public static List<SuppliedField> forOsmFacility(
            NormalizedMunicipalFacility facility, boolean osmNameTagPresent) {
        Objects.requireNonNull(facility, "facility");
        List<SuppliedField> fields = new ArrayList<>();
        if (osmNameTagPresent && hasText(facility.displayName())) {
            fields.add(new SuppliedField(RegistryField.NAME));
        }
        fields.add(new SuppliedField(RegistryField.COORDINATES));
        if (hasText(facility.operatorName())) {
            fields.add(new SuppliedField(RegistryField.OPERATOR));
        }
        if (facility.facilityType() != null) {
            fields.add(new SuppliedField(RegistryField.FACILITY_TYPE));
        }
        if (facility.capacityTotal() != null) {
            fields.add(new SuppliedField(RegistryField.STATIC_CAPACITY));
        }
        fields.add(new SuppliedField(RegistryField.ATTRIBUTION));
        return List.copyOf(fields);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private IngestFieldProvenancePolicy() {}
}
