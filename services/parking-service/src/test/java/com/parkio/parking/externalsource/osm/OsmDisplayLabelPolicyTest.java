package com.parkio.parking.externalsource.osm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OsmDisplayLabelPolicyTest {

    @Test
    void nameTrPreferredOverName() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1,
                "way/1",
                Map.of("name:tr", "Konak Otoparkı", "name", "Konak Parking"));
        assertThat(s.displayLabel()).isEqualTo("Konak Otoparkı");
        assertThat(s.outcome()).isEqualTo(OsmDisplayLabelOutcome.LOCALIZED_NAME_SELECTED);
        assertThat(s.nameBearing()).isTrue();
    }

    @Test
    void namePreferredOverOfficialName() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1,
                "way/1",
                Map.of("name", "Alsancak Garaj", "official_name", "Alsancak Official"));
        assertThat(s.displayLabel()).isEqualTo("Alsancak Garaj");
        assertThat(s.outcome()).isEqualTo(OsmDisplayLabelOutcome.REAL_NAME_SELECTED);
    }

    @Test
    void officialNamePreferredOverShortName() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1,
                "way/1",
                Map.of("official_name", "Resmi Ad", "short_name", "Kısa"));
        assertThat(s.displayLabel()).isEqualTo("Resmi Ad");
        assertThat(s.outcome()).isEqualTo(OsmDisplayLabelOutcome.REAL_NAME_SELECTED);
        assertThat(s.nameBearing()).isTrue();
    }

    @Test
    void operatorFallback() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1,
                "way/1",
                Map.of("operator", "İzmir Büyükşehir"));
        assertThat(s.displayLabel()).isEqualTo("İzmir Büyükşehir Otoparkı");
        assertThat(s.outcome()).isEqualTo(OsmDisplayLabelOutcome.OPERATOR_FALLBACK);
        assertThat(s.nameBearing()).isFalse();
    }

    @Test
    void brandFallback() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1,
                "way/1",
                Map.of("brand", "Parkio"));
        assertThat(s.displayLabel()).isEqualTo("Parkio Otoparkı");
        assertThat(s.outcome()).isEqualTo(OsmDisplayLabelOutcome.BRAND_FALLBACK);
        assertThat(s.nameBearing()).isFalse();
    }

    @Test
    void typeSpecificFallbacks() {
        assertThat(OsmDisplayLabelPolicy.select(OsmDisplayLabelPolicy.POLICY_V1, "way/1",
                Map.of("parking", "multi-storey")).displayLabel()).isEqualTo("Katlı Otopark");
        assertThat(OsmDisplayLabelPolicy.select(OsmDisplayLabelPolicy.POLICY_V1, "way/2",
                Map.of("underground", "yes")).displayLabel()).isEqualTo("Yer Altı Otoparkı");
        assertThat(OsmDisplayLabelPolicy.select(OsmDisplayLabelPolicy.POLICY_V1, "way/3",
                Map.of("covered", "yes")).displayLabel()).isEqualTo("Kapalı Otopark");
        assertThat(OsmDisplayLabelPolicy.select(OsmDisplayLabelPolicy.POLICY_V1, "way/4",
                Map.of("park_ride", "yes")).displayLabel()).isEqualTo("Park Et ve Devam Et Otoparkı");
        assertThat(OsmDisplayLabelPolicy.select(OsmDisplayLabelPolicy.POLICY_V1, "way/5",
                Map.of("parking", "surface")).displayLabel()).isEqualTo("Açık Otopark");
        assertThat(OsmDisplayLabelPolicy.select(OsmDisplayLabelPolicy.POLICY_V1, "way/5",
                Map.of("parking", "surface")).outcome()).isEqualTo(OsmDisplayLabelOutcome.TYPE_FALLBACK);
    }

    @Test
    void neutralFallback() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1, "relation/9", Map.of("amenity", "parking"));
        assertThat(s.displayLabel()).isEqualTo("Otopark");
        assertThat(s.outcome()).isEqualTo(OsmDisplayLabelOutcome.NEUTRAL_FALLBACK);
        assertThat(s.nameBearing()).isFalse();
        assertThat(s.displayLabel().toLowerCase(Locale.ROOT)).doesNotContain("relation/");
        assertThat(s.displayLabel()).doesNotContain("9");
    }

    @Test
    void blankNamesRejected() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1,
                "way/1",
                Map.of("name", "   ", "parking", "surface"));
        assertThat(s.displayLabel()).isEqualTo("Açık Otopark");
        assertThat(s.rejectedCandidateCount()).isGreaterThan(0);
    }

    @Test
    void technicalOsmIdLabelsRejected() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1,
                "way/123456",
                Map.of("name", "way/123456", "operator", "ABC"));
        assertThat(s.displayLabel()).isEqualTo("ABC Otoparkı");
        assertThat(s.technicalIdRejectedCount()).isGreaterThan(0);
        assertThat(s.displayLabel().toLowerCase(Locale.ROOT)).doesNotContain("way/");
    }

    @Test
    void urlPhoneCoordinateLikeRejected() {
        assertThat(OsmDisplayLabelPolicy.select(
                        OsmDisplayLabelPolicy.POLICY_V1,
                        "way/1",
                        Map.of("name", "https://example.com/lot", "parking", "surface"))
                .displayLabel()).isEqualTo("Açık Otopark");
        assertThat(OsmDisplayLabelPolicy.select(
                        OsmDisplayLabelPolicy.POLICY_V1,
                        "way/1",
                        Map.of("name", "+90 232 123 4567", "parking", "surface"))
                .displayLabel()).isEqualTo("Açık Otopark");
        assertThat(OsmDisplayLabelPolicy.select(
                        OsmDisplayLabelPolicy.POLICY_V1,
                        "way/1",
                        Map.of("name", "38.41, 27.12", "parking", "surface"))
                .displayLabel()).isEqualTo("Açık Otopark");
    }

    @Test
    void excessiveLengthRejected() {
        String longName = "A".repeat(OsmDisplayLabelPolicy.MAX_PUBLIC_LABEL_LENGTH + 1);
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1,
                "way/1",
                Map.of("name", longName));
        assertThat(s.displayLabel()).isEqualTo("Otopark");
        assertThat(s.rejectedCandidateCount()).isGreaterThan(0);
    }

    @Test
    void turkishCharactersPreservedAndWhitespaceNormalized() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1,
                "way/1",
                Map.of("name:tr", "  Kemeraltı   Çarşısı   Otoparkı  "));
        assertThat(s.displayLabel()).isEqualTo("Kemeraltı Çarşısı Otoparkı");
    }

    @Test
    void deterministicOutput() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("operator", "Belediye");
        tags.put("brand", "IgnoredWhenOperatorPresent");
        OsmDisplayLabelSelection a = OsmDisplayLabelPolicy.select(OsmDisplayLabelPolicy.POLICY_V1, "way/1", tags);
        OsmDisplayLabelSelection b = OsmDisplayLabelPolicy.select(OsmDisplayLabelPolicy.POLICY_V1, "way/1", tags);
        assertThat(a).isEqualTo(b);
        assertThat(a.displayLabel()).isEqualTo("Belediye Otoparkı");
    }

    @Test
    void noRawExternalIdInFallback() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1, "node/999888777", Map.of());
        assertThat(s.displayLabel()).doesNotContain("999888777");
        assertThat(s.displayLabel().toLowerCase(Locale.ROOT)).doesNotContain("node/");
        assertThat(s.displayLabel()).doesNotContain("OSM");
    }

    @Test
    void realNameWritesNameBearingTrue() {
        assertThat(OsmDisplayLabelPolicy.select(
                        OsmDisplayLabelPolicy.POLICY_V1, "way/1", Map.of("name", "Gerçek Ad"))
                .nameBearing()).isTrue();
        assertThat(OsmDisplayLabelPolicy.select(
                        OsmDisplayLabelPolicy.POLICY_V1, "way/1", Map.of("name:tr", "Türkçe Ad"))
                .nameBearing()).isTrue();
        assertThat(OsmDisplayLabelPolicy.select(
                        OsmDisplayLabelPolicy.POLICY_V1, "way/1", Map.of("official_name", "Resmi"))
                .nameBearing()).isTrue();
        assertThat(OsmDisplayLabelPolicy.select(
                        OsmDisplayLabelPolicy.POLICY_V1, "way/1", Map.of("short_name", "Kısa"))
                .nameBearing()).isTrue();
    }

    @Test
    void fallbacksDoNotClaimNameBearing() {
        assertThat(OsmDisplayLabelPolicy.select(
                        OsmDisplayLabelPolicy.POLICY_V1, "way/1", Map.of("operator", "Op"))
                .nameBearing()).isFalse();
        assertThat(OsmDisplayLabelPolicy.select(
                        OsmDisplayLabelPolicy.POLICY_V1, "way/1", Map.of("brand", "Br"))
                .nameBearing()).isFalse();
        assertThat(OsmDisplayLabelPolicy.select(
                        OsmDisplayLabelPolicy.POLICY_V1, "way/1", Map.of())
                .nameBearing()).isFalse();
    }

    @Test
    void legacyPolicyKeepsTechnicalFallback() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_LEGACY, "way/42", Map.of());
        assertThat(s.displayLabel()).isEqualTo("OSM parking way/42");
        assertThat(s.outcome()).isEqualTo(OsmDisplayLabelOutcome.LEGACY_TECHNICAL);
        assertThat(s.nameBearing()).isFalse();
    }

    @Test
    void rejectsKnownNonNameLiterals() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1,
                "way/1",
                Map.of("name", "unnamed", "operator", "X"));
        assertThat(s.displayLabel()).isEqualTo("X Otoparkı");
    }

    @Test
    void avoidsDuplicateOtoparkComposition() {
        OsmDisplayLabelSelection s = OsmDisplayLabelPolicy.select(
                OsmDisplayLabelPolicy.POLICY_V1,
                "way/1",
                Map.of("operator", "Merkez Otopark"));
        assertThat(s.displayLabel()).isEqualTo("Merkez Otopark");
        assertThat(s.displayLabel().toLowerCase(Locale.ROOT)).doesNotContain("otopark otopark");
    }
}
