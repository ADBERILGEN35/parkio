package com.parkio.parking.externalsource.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.MunicipalSourcePublicationPolicy;
import com.parkio.parking.infrastructure.config.IzelmanProperties;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublicProvenancePublicationPolicyTest {
    private PublicProvenancePublicationPolicy policy;

    @BeforeEach
    void setUp() {
        MunicipalSourceProperties municipal = new MunicipalSourceProperties();
        municipal.getOsm().setPublicationEnabled(true);
        IzelmanProperties izelman = new IzelmanProperties();
        izelman.setFacilityPublicationEnabled(false);
        policy = new PublicProvenancePublicationPolicy(
                new MunicipalSourcePublicationPolicy(municipal, izelman));
    }

    @Test
    void projectsAllowListedPublishableFieldsAsSourceKeyOnly() {
        var result = policy.project(List.of(
                new PublicProvenancePublicationPolicy.FieldSource("NAME", MunicipalSourceIdentity.IZUM),
                new PublicProvenancePublicationPolicy.FieldSource(
                        "COORDINATES", MunicipalSourceIdentity.OSM),
                new PublicProvenancePublicationPolicy.FieldSource(
                        "TARIFF_ASSIGNMENT", "izelman-open-parking-facilities"),
                new PublicProvenancePublicationPolicy.FieldSource(
                        "ACCESS", MunicipalSourceIdentity.OSM)));

        assertThat(result.selectedFieldProvenanceSummary())
                .containsEntry("NAME", MunicipalSourceIdentity.IZUM)
                .containsEntry("COORDINATES", MunicipalSourceIdentity.OSM)
                .doesNotContainKey("TARIFF_ASSIGNMENT")
                .doesNotContainKey("ACCESS");
        assertThat(result.contributingSourceKeys())
                .containsExactly(MunicipalSourceIdentity.IZUM, MunicipalSourceIdentity.OSM);
        assertThat(result.selectedFieldProvenanceSummary().values())
                .noneMatch(value -> value.contains(":") || value.contains("REVIEW") || value.contains("CURRENT"));
    }

    @Test
    void omitsUnpublishedIzelmanSources() {
        var result = policy.project(List.of(
                new PublicProvenancePublicationPolicy.FieldSource(
                        "NAME", "izelman-open-parking-facilities"),
                new PublicProvenancePublicationPolicy.FieldSource(
                        "ADDRESS", MunicipalSourceIdentity.IZUM)));

        assertThat(result.selectedFieldProvenanceSummary())
                .containsOnlyKeys("ADDRESS")
                .containsEntry("ADDRESS", MunicipalSourceIdentity.IZUM);
        assertThat(result.contributingSourceKeys()).containsExactly(MunicipalSourceIdentity.IZUM);
    }

    @Test
    void nullOrEmptyRowsYieldEmptyProjection() {
        assertThat(policy.project(null).isEmpty()).isTrue();
        assertThat(policy.project(List.of()).isEmpty()).isTrue();
    }

    @Test
    void sourceFamilyLabelIsBounded() {
        assertThat(PublicProvenancePublicationPolicy.sourceFamilyLabel(List.of()))
                .isEqualTo("none");
        assertThat(PublicProvenancePublicationPolicy.sourceFamilyLabel(
                        List.of(MunicipalSourceIdentity.IZUM)))
                .isEqualTo("izum");
        assertThat(PublicProvenancePublicationPolicy.sourceFamilyLabel(
                        List.of(MunicipalSourceIdentity.OSM)))
                .isEqualTo("osm");
        assertThat(PublicProvenancePublicationPolicy.sourceFamilyLabel(
                        List.of(MunicipalSourceIdentity.IZUM, MunicipalSourceIdentity.OSM)))
                .isEqualTo("mixed");
    }
}
