package com.parkio.parking.decision.authority;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.domain.ParkingSpotStatus;
import org.junit.jupiter.api.Test;

class AuthorityDispositionCompatibilityTest {

    @Test
    void exhaustiveMatrixHasEntryForEveryPair() {
        for (ParkingSpotStatus status : ParkingSpotStatus.values()) {
            for (PublicationDisposition disposition : PublicationDisposition.values()) {
                assertThat(AuthorityDispositionCompatibility.classify(status, disposition))
                        .as("%s x %s", status, disposition)
                        .isNotNull();
            }
        }
    }

    @Test
    void onlyPendingValidationFullPublishIsApplySupported() {
        assertThat(AuthorityDispositionCompatibility.classify(
                        ParkingSpotStatus.PENDING_VALIDATION, PublicationDisposition.FULL_PUBLISH))
                .isEqualTo(AuthorityTransitionClass.APPLY_SUPPORTED);
        assertThat(AuthorityDispositionCompatibility.isCanaryAuthorityDisposition(
                        PublicationDisposition.FULL_PUBLISH))
                .isTrue();
        assertThat(AuthorityDispositionCompatibility.isCanaryAuthorityDisposition(
                        PublicationDisposition.REJECTED))
                .isFalse();
        assertThat(AuthorityDispositionCompatibility.isCanaryAuthorityDisposition(
                        PublicationDisposition.HOLD))
                .isFalse();
        assertThat(AuthorityDispositionCompatibility.classify(
                        ParkingSpotStatus.PENDING_VALIDATION, PublicationDisposition.REJECTED))
                .isEqualTo(AuthorityTransitionClass.LEGACY_ONLY);
        assertThat(AuthorityDispositionCompatibility.classify(
                        ParkingSpotStatus.PENDING_VALIDATION, PublicationDisposition.SHADOW))
                .isEqualTo(AuthorityTransitionClass.FUTURE_UNSUPPORTED);
        assertThat(AuthorityDispositionCompatibility.classify(
                        ParkingSpotStatus.PENDING_VALIDATION, PublicationDisposition.LIMITED_PUBLISH))
                .isEqualTo(AuthorityTransitionClass.FUTURE_UNSUPPORTED);
        assertThat(AuthorityDispositionCompatibility.classify(
                        ParkingSpotStatus.PENDING_VALIDATION, PublicationDisposition.EXPIRED))
                .isEqualTo(AuthorityTransitionClass.FUTURE_UNSUPPORTED);
    }
}