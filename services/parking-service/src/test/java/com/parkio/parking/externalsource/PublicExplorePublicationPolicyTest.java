package com.parkio.parking.externalsource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PublicExplorePublicationPolicyTest {
    @Test
    void onlyReviewedFamiliesAreCodeSupportedForPublication() {
        assertThat(PublicExplorePublicationPolicy.reviewedFamilies())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("IZUM", "ISPARK", "IZELMAN", "OSM");
        assertThat(PublicExplorePublicationPolicy.ReviewedPublicFamily.IZUM.sourceKeys())
                .containsExactly(MunicipalSourceIdentity.IZUM);
        assertThat(PublicExplorePublicationPolicy.ReviewedPublicFamily.ISPARK.sourceKeys())
                .containsExactly(MunicipalSourceIdentity.ISPARK);
        assertThat(PublicExplorePublicationPolicy.ReviewedPublicFamily.IZELMAN.sourceKeys())
                .containsExactlyInAnyOrder(
                        IzelmanSourceKeys.OPEN, IzelmanSourceKeys.CLOSED, IzelmanSourceKeys.BARRIER);
        assertThat(PublicExplorePublicationPolicy.ReviewedPublicFamily.OSM.sourceKeys())
                .containsExactly(MunicipalSourceIdentity.OSM);
    }

    @Test
    void parseAcceptsReviewedRejectsUnreviewedAndUnknown() {
        assertThat(PublicExplorePublicationPolicy.parseAllowedFamilies(
                        List.of("IZUM", "ISPARK", "IZELMAN", "OSM", "OPENSTREETMAP")))
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("IZUM", "ISPARK", "IZELMAN", "OSM");
        assertThatThrownBy(() -> PublicExplorePublicationPolicy.parseAllowedFamilies(List.of("ANPARK")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreviewed");
        assertThatThrownBy(() -> PublicExplorePublicationPolicy.parseAllowedFamilies(List.of("NOT_A_PROVIDER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
    }

    @Test
    void sourceKeysNeverInventUnknownProvidersFromEnvStringsAlone() {
        Set<String> keys = PublicExplorePublicationPolicy.sourceKeysFor(
                PublicExplorePublicationPolicy.parseAllowedFamilies(List.of("IZUM")));
        assertThat(keys).containsExactly(MunicipalSourceIdentity.IZUM);
        assertThat(PublicExplorePublicationPolicy.sourceKeysFor(Set.of())).isEmpty();
    }
}
