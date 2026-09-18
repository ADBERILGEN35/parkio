package com.parkio.parking.externalsource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PublicExplorePublicationPolicyTest {
    @Test
    void onlyReviewedFamiliesAreCodeSupportedForPublication() {
        assertThat(PublicExplorePublicationPolicy.reviewedFamilies())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("IZUM", "ISPARK");
        assertThat(PublicExplorePublicationPolicy.ReviewedPublicFamily.IZUM.sourceKeys())
                .containsExactly(MunicipalSourceIdentity.IZUM);
        assertThat(PublicExplorePublicationPolicy.ReviewedPublicFamily.ISPARK.sourceKeys())
                .containsExactly(MunicipalSourceIdentity.ISPARK);
    }

    @Test
    void parseAcceptsReviewedRejectsUnreviewedAndUnknown() {
        assertThat(PublicExplorePublicationPolicy.parseAllowedFamilies(List.of("IZUM", "ISPARK")))
                .hasSize(2);
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
