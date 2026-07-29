package com.parkio.parking.decision.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.domain.ParkingSpotStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ShadowDecisionComparatorTest {

    @ParameterizedTest
    @EnumSource(ParkingSpotStatus.class)
    void everyLegacyStatusHasExhaustiveShadowMapping(ParkingSpotStatus status) {
        for (PublicationDisposition disposition : PublicationDisposition.values()) {
            ShadowComparisonCategory category =
                    ShadowDecisionComparator.compareStatus(status, disposition);
            assertThat(category).isNotNull();
        }
    }

    @Test
    void activeAndFullPublishAreEquivalent() {
        assertThat(ShadowDecisionComparator.compareStatus(
                        ParkingSpotStatus.ACTIVE, PublicationDisposition.FULL_PUBLISH))
                .isEqualTo(ShadowComparisonCategory.EQUIVALENT);
    }

    @Test
    void pendingReviewAndHoldAreLegacyReviewShadowHold() {
        assertThat(ShadowDecisionComparator.compareStatus(
                        ParkingSpotStatus.PENDING_REVIEW, PublicationDisposition.HOLD))
                .isEqualTo(ShadowComparisonCategory.LEGACY_REVIEW_SHADOW_HOLD);
    }

    @Test
    void limitedPublishHasNoSafeEquivalenceWithVisibleLegacy() {
        assertThat(ShadowDecisionComparator.compareStatus(
                        ParkingSpotStatus.ACTIVE, PublicationDisposition.LIMITED_PUBLISH))
                .isEqualTo(ShadowComparisonCategory.NO_SAFE_EQUIVALENCE);
    }

    @Test
    void staleKindIsNotComparable() {
        assertThat(ShadowDecisionComparator.compare(
                        ParkingSpotStatus.ACTIVE,
                        PublicationDisposition.FULL_PUBLISH,
                        LegacyPublicationOutcome.Kind.STALE))
                .isEqualTo(ShadowComparisonCategory.NOT_COMPARABLE);
    }

    @Test
    void reviewFailedIsNotComparable() {
        assertThat(ShadowDecisionComparator.compareStatus(
                        ParkingSpotStatus.REVIEW_FAILED, PublicationDisposition.HOLD))
                .isEqualTo(ShadowComparisonCategory.NOT_COMPARABLE);
    }
}
