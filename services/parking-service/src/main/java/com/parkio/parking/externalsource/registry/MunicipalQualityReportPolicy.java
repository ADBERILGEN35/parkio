package com.parkio.parking.externalsource.registry;

import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.osm.OsmDisplayLabelOutcome;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DATA-WP-15: bounds the operator quality/coverage report to allow-listed fields,
 * supported source keys and known label outcomes. The report is read-only and never
 * exposes raw payloads, hashes, correlation ids, a global quality score, linking
 * readiness or production-readiness verdicts.
 */
public final class MunicipalQualityReportPolicy {
    public static final String POLICY_VERSION = "municipal-quality-report-v1";

    /** Same allow-list as {@link PublicProvenancePublicationPolicy#PUBLIC_FIELD_ALLOWLIST}. */
    public static final Set<String> ALLOWED_PROVENANCE_FIELDS = Set.of(
            RegistryField.NAME.name(),
            RegistryField.COORDINATES.name(),
            RegistryField.ADDRESS.name(),
            RegistryField.OPERATOR.name(),
            RegistryField.FACILITY_TYPE.name(),
            RegistryField.STATIC_CAPACITY.name(),
            RegistryField.ATTRIBUTION.name());

    /** Deterministic output order for provenance coverage rows. */
    public static final List<String> PROVENANCE_FIELD_ORDER = List.of(
            RegistryField.NAME.name(),
            RegistryField.COORDINATES.name(),
            RegistryField.ADDRESS.name(),
            RegistryField.OPERATOR.name(),
            RegistryField.FACILITY_TYPE.name(),
            RegistryField.STATIC_CAPACITY.name(),
            RegistryField.ATTRIBUTION.name());

    /** Keys copied out of a persisted OSM import {@code quality_report_json}. */
    public static final Set<String> ALLOWED_QUALITY_REPORT_KEYS = Set.of(
            "named", "unnamed", "capacityKnown", "rejectReasons",
            "clipVersion", "labelPolicyVersion", "labelOutcomes");

    /** Label outcomes that did not carry a real source name (stale-NAME mismatch input). */
    public static final Set<String> FALLBACK_LABEL_OUTCOMES = Set.of(
            OsmDisplayLabelOutcome.OPERATOR_FALLBACK.metricOutcome(),
            OsmDisplayLabelOutcome.BRAND_FALLBACK.metricOutcome(),
            OsmDisplayLabelOutcome.TYPE_FALLBACK.metricOutcome(),
            OsmDisplayLabelOutcome.NEUTRAL_FALLBACK.metricOutcome(),
            OsmDisplayLabelOutcome.LEGACY_TECHNICAL.metricOutcome());

    public static final Set<String> NAME_BEARING_OUTCOMES = Set.of(
            OsmDisplayLabelOutcome.REAL_NAME_SELECTED.metricOutcome(),
            OsmDisplayLabelOutcome.LOCALIZED_NAME_SELECTED.metricOutcome());

    /** Every outcome the label policy can emit; anything else is dropped as unknown. */
    public static final Set<String> KNOWN_LABEL_OUTCOMES = Arrays.stream(OsmDisplayLabelOutcome.values())
            .map(OsmDisplayLabelOutcome::metricOutcome)
            .collect(Collectors.toUnmodifiableSet());

    /** Sources with a modelled quality section. Anything else is reported as not found. */
    public static final Set<String> SUPPORTED_SOURCE_KEYS = Set.of(
            MunicipalSourceIdentity.OSM, MunicipalSourceIdentity.IZUM);

    /** Upper bound on reject-reason cardinality copied into a response. */
    public static final int MAX_REJECT_REASON_KEYS = 50;

    /** Upper bound on any bounded text value copied out of persisted JSON. */
    public static final int MAX_TEXT_LENGTH = 128;

    private MunicipalQualityReportPolicy() {}

    public static boolean isSupported(String sourceKey) {
        return sourceKey != null && SUPPORTED_SOURCE_KEYS.contains(sourceKey);
    }
}
