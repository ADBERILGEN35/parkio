package com.parkio.parking.externalsource.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.externalsource.osm.OsmDisplayLabelOutcome;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * DATA-WP-15: the quality-report allow-lists, supported source keys and bounds are the
 * only surface the report may expose. Anything outside them must be dropped or rejected.
 */
class MunicipalQualityReportPolicyTest {

    @Test
    void supportsOnlyOsmAndIzum() {
        assertThat(MunicipalQualityReportPolicy.isSupported(MunicipalSourceIdentity.OSM)).isTrue();
        assertThat(MunicipalQualityReportPolicy.isSupported(MunicipalSourceIdentity.IZUM)).isTrue();
        assertThat(MunicipalQualityReportPolicy.SUPPORTED_SOURCE_KEYS)
                .containsExactlyInAnyOrder(MunicipalSourceIdentity.OSM, MunicipalSourceIdentity.IZUM);
    }

    @Test
    void rejectsUnknownBlankAndNullSourceKeys() {
        assertThat(MunicipalQualityReportPolicy.isSupported(null)).isFalse();
        assertThat(MunicipalQualityReportPolicy.isSupported("")).isFalse();
        assertThat(MunicipalQualityReportPolicy.isSupported("   ")).isFalse();
        assertThat(MunicipalQualityReportPolicy.isSupported("OSM-GEOFABRIK-TURKEY")).isFalse();
        assertThat(MunicipalQualityReportPolicy.isSupported(IzelmanSourceKeys.OPEN)).isFalse();
        assertThat(MunicipalQualityReportPolicy.isSupported(IzelmanSourceKeys.TARIFFS)).isFalse();
        assertThat(MunicipalQualityReportPolicy.isSupported("../../etc/passwd")).isFalse();
    }

    @Test
    void provenanceAllowListMatchesDeterministicOrder() {
        assertThat(MunicipalQualityReportPolicy.PROVENANCE_FIELD_ORDER).containsExactly(
                "NAME", "COORDINATES", "ADDRESS", "OPERATOR",
                "FACILITY_TYPE", "STATIC_CAPACITY", "ATTRIBUTION");
        assertThat(MunicipalQualityReportPolicy.ALLOWED_PROVENANCE_FIELDS)
                .containsExactlyInAnyOrderElementsOf(MunicipalQualityReportPolicy.PROVENANCE_FIELD_ORDER);
        assertThat(MunicipalQualityReportPolicy.PROVENANCE_FIELD_ORDER)
                .doesNotHaveDuplicates();
    }

    @Test
    void provenanceAllowListExcludesInternalOnlyFields() {
        assertThat(MunicipalQualityReportPolicy.ALLOWED_PROVENANCE_FIELDS)
                .doesNotContain("TARIFF_ASSIGNMENT", "ACCESS", "OPENING_STATUS", "DISTRICT");
    }

    @Test
    void allowedQualityReportKeysExcludeScoresAndRawPayloads() {
        assertThat(MunicipalQualityReportPolicy.ALLOWED_QUALITY_REPORT_KEYS).containsExactlyInAnyOrder(
                "named", "unnamed", "capacityKnown", "rejectReasons",
                "clipVersion", "labelPolicyVersion", "labelOutcomes");
        assertThat(MunicipalQualityReportPolicy.ALLOWED_QUALITY_REPORT_KEYS).doesNotContain(
                "qualityScore", "trustScore", "readinessScore", "linkingReadiness",
                "productionReady", "sha256", "payloadHash", "correlationId", "rawPayload");
    }

    @Test
    void knownLabelOutcomesCoverEveryEnumValueAndNothingElse() {
        Set<String> expected = Arrays.stream(OsmDisplayLabelOutcome.values())
                .map(OsmDisplayLabelOutcome::metricOutcome)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertThat(MunicipalQualityReportPolicy.KNOWN_LABEL_OUTCOMES)
                .containsExactlyInAnyOrderElementsOf(expected)
                .hasSize(OsmDisplayLabelOutcome.values().length)
                .doesNotContain("unknown", "bogus_outcome");
    }

    @Test
    void nameBearingAndFallbackOutcomesArePartitionedAndConsistentWithTheEnum() {
        assertThat(MunicipalQualityReportPolicy.NAME_BEARING_OUTCOMES).containsExactlyInAnyOrder(
                OsmDisplayLabelOutcome.REAL_NAME_SELECTED.metricOutcome(),
                OsmDisplayLabelOutcome.LOCALIZED_NAME_SELECTED.metricOutcome());
        assertThat(MunicipalQualityReportPolicy.FALLBACK_LABEL_OUTCOMES).containsExactlyInAnyOrder(
                OsmDisplayLabelOutcome.OPERATOR_FALLBACK.metricOutcome(),
                OsmDisplayLabelOutcome.BRAND_FALLBACK.metricOutcome(),
                OsmDisplayLabelOutcome.TYPE_FALLBACK.metricOutcome(),
                OsmDisplayLabelOutcome.NEUTRAL_FALLBACK.metricOutcome(),
                OsmDisplayLabelOutcome.LEGACY_TECHNICAL.metricOutcome());

        assertThat(MunicipalQualityReportPolicy.NAME_BEARING_OUTCOMES)
                .doesNotContainAnyElementsOf(MunicipalQualityReportPolicy.FALLBACK_LABEL_OUTCOMES);
        assertThat(MunicipalQualityReportPolicy.KNOWN_LABEL_OUTCOMES)
                .containsAll(MunicipalQualityReportPolicy.NAME_BEARING_OUTCOMES)
                .containsAll(MunicipalQualityReportPolicy.FALLBACK_LABEL_OUTCOMES);

        // Every name-bearing outcome is exactly the set the enum flags as name bearing.
        assertThat(Arrays.stream(OsmDisplayLabelOutcome.values())
                        .filter(OsmDisplayLabelOutcome::nameBearing)
                        .map(OsmDisplayLabelOutcome::metricOutcome)
                        .toList())
                .containsExactlyInAnyOrderElementsOf(MunicipalQualityReportPolicy.NAME_BEARING_OUTCOMES);
    }

    @Test
    void boundsAndPolicyVersionAreStable() {
        assertThat(MunicipalQualityReportPolicy.POLICY_VERSION).isEqualTo("municipal-quality-report-v1");
        assertThat(MunicipalQualityReportPolicy.MAX_REJECT_REASON_KEYS).isEqualTo(50);
        assertThat(MunicipalQualityReportPolicy.MAX_TEXT_LENGTH).isEqualTo(128);
    }

    @Test
    void allowListsAreImmutable() {
        assertThatThrownBy(() -> MunicipalQualityReportPolicy.ALLOWED_PROVENANCE_FIELDS.add("ACCESS"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> MunicipalQualityReportPolicy.ALLOWED_QUALITY_REPORT_KEYS.add("qualityScore"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> MunicipalQualityReportPolicy.KNOWN_LABEL_OUTCOMES.add("bogus"))
                .isInstanceOf(UnsupportedOperationException.class);
        List<String> order = MunicipalQualityReportPolicy.PROVENANCE_FIELD_ORDER;
        assertThatThrownBy(() -> order.add("ACCESS"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
