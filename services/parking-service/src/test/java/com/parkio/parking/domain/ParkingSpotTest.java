package com.parkio.parking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.domain.exception.ParkingException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-domain invariant tests for {@link ParkingSpot} creation, the AI gate and the
 * moderation lifetime rule: a spot's advertised visibility is never consumed while it
 * waits on moderation, and it starts exactly once, at publication.
 */
class ParkingSpotTest {

    private static final Instant NOW = Instant.parse("2026-06-07T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final ModerationPolicy POLICY = new ModerationPolicy(
            TTL, Duration.ofMinutes(2), Duration.ofMinutes(1), 3,
            Duration.ofMinutes(15), Duration.ofMinutes(30));

    private static ParkingSpot create(LegalStatus legalStatus, Set<VehicleType> vehicleTypes,
                                      double latitude, double longitude) {
        return ParkingSpot.create(UUID.randomUUID(), UUID.randomUUID(), latitude, longitude, null, null,
                false, vehicleTypes, ParkingContext.STREET_PARKING, legalStatus, Set.of(), NOW, POLICY);
    }

    private static ParkingSpot createSpot() {
        return create(LegalStatus.LEGAL, Set.of(VehicleType.SEDAN), 41.0, 29.0);
    }

    private static ParkingSpot createActive() {
        ParkingSpot spot = createSpot();
        assertThat(spot.applyAiValidationPassed(NOW, POLICY)).isTrue();
        return spot;
    }

    @Test
    void createsPendingValidationSpotHiddenFromSearchWithLifetimeNotStarted() {
        ParkingSpot spot = createSpot();

        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.PENDING_VALIDATION);
        assertThat(spot.isVisibleForSearch(NOW)).isFalse();
        assertThat(spot.isTerminal()).isFalse();
        // The advertised lifetime has not begun: no activation instant and no expiry.
        assertThat(spot.activatedAt()).isNull();
        assertThat(spot.expiresAt()).isNull();
        assertThat(spot.moderationDeadlineAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
    }

    @Test
    void pendingSpotIsNeverTimeExpiredHoweverLongModerationTakes() {
        ParkingSpot spot = createSpot();
        Instant muchLater = NOW.plus(Duration.ofDays(30));

        assertThat(spot.isTimeExpired(muchLater)).isFalse();
        assertThat(spot.markExpired(muchLater)).isFalse();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.PENDING_VALIDATION);
    }

    @Test
    void applyAiValidationPassedPromotesToActiveAndVisible() {
        ParkingSpot spot = createSpot();

        assertThat(spot.applyAiValidationPassed(NOW.plusSeconds(1), POLICY)).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(spot.isVisibleForSearch(NOW.plusSeconds(1))).isTrue();
        assertThat(spot.applyAiValidationPassed(NOW.plusSeconds(2), POLICY)).isFalse();
    }

    @Test
    void approvalStartsTheFullLifetimeFromTheApprovalInstant() {
        ParkingSpot spot = createSpot();
        Instant approvedAt = NOW.plusSeconds(30);

        assertThat(spot.applyAiValidationPassed(approvedAt, POLICY)).isTrue();

        assertThat(spot.activatedAt()).isEqualTo(approvedAt);
        assertThat(spot.expiresAt()).isEqualTo(approvedAt.plus(TTL));
    }

    @Test
    void delayedButStillFreshApprovalGrantsTheFullLifetime() {
        ParkingSpot spot = createSpot();
        // Within maxPublishableAge (30m) but well past the old creation-time TTL.
        Instant approvedAt = NOW.plus(Duration.ofMinutes(20));

        assertThat(spot.applyAiValidationPassed(approvedAt, POLICY)).isTrue();

        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(spot.expiresAt()).isEqualTo(approvedAt.plus(TTL));
        assertThat(spot.isVisibleForSearch(approvedAt.plusSeconds(1))).isTrue();
    }

    @Test
    void approvalPastMaxPublishableAgeFailsAsStaleInsteadOfPublishing() {
        ParkingSpot spot = createSpot();
        Instant approvedAt = NOW.plus(Duration.ofMinutes(31));

        assertThat(spot.applyAiValidationPassed(approvedAt, POLICY)).isTrue();

        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.REVIEW_FAILED);
        assertThat(spot.activatedAt()).isNull();
        assertThat(spot.expiresAt()).isNull();
        assertThat(spot.isVisibleForSearch(approvedAt)).isFalse();
    }

    @Test
    void applyAiValidationUncertainMovesToPendingReviewStillHidden() {
        ParkingSpot spot = createSpot();

        assertThat(spot.applyAiValidationUncertain(NOW.plusSeconds(1), POLICY)).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.PENDING_REVIEW);
        assertThat(spot.isVisibleForSearch(NOW.plusSeconds(1))).isFalse();
        assertThat(spot.applyAiValidationUncertain(NOW.plusSeconds(2), POLICY)).isFalse();
        // Landing in review restarts the deadline against the human window.
        assertThat(spot.moderationDeadlineAt()).isEqualTo(NOW.plusSeconds(1).plus(Duration.ofMinutes(15)));

        assertThat(spot.applyAiValidationPassed(NOW.plusSeconds(3), POLICY)).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
    }

    @Test
    void moderatorApprovalPublishesPendingReviewSpotAndStartsLifetimeExactlyOnce() {
        ParkingSpot spot = createSpot();
        assertThat(spot.applyAiValidationUncertain(NOW.plusSeconds(1), POLICY)).isTrue();
        Instant approvedAt = NOW.plus(Duration.ofMinutes(10));

        assertThat(spot.applyModeratorApproval(approvedAt, POLICY)).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(spot.expiresAt()).isEqualTo(approvedAt.plus(TTL));

        // A duplicate approval must not extend the spot's life a second time.
        assertThat(spot.applyModeratorApproval(approvedAt.plusSeconds(60), POLICY)).isFalse();
        assertThat(spot.activatedAt()).isEqualTo(approvedAt);
        assertThat(spot.expiresAt()).isEqualTo(approvedAt.plus(TTL));
    }

    @Test
    void applyAiValidationRejectedIsTerminalAndHidden() {
        ParkingSpot spot = createSpot();

        assertThat(spot.applyAiValidationRejected(NOW.plusSeconds(1))).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.REJECTED);
        assertThat(spot.isTerminal()).isTrue();
        assertThat(spot.isVisibleForSearch(NOW.plusSeconds(1))).isFalse();
        assertThat(spot.applyAiValidationRejected(NOW.plusSeconds(2))).isFalse();
    }

    @Test
    void rejectedAndReviewFailedSpotsCanNeverBecomeActive() {
        ParkingSpot rejected = createSpot();
        assertThat(rejected.applyAiValidationRejected(NOW.plusSeconds(1))).isTrue();
        assertThat(rejected.applyAiValidationPassed(NOW.plusSeconds(2), POLICY)).isFalse();
        assertThat(rejected.applyModeratorApproval(NOW.plusSeconds(3), POLICY)).isFalse();
        assertThat(rejected.status()).isEqualTo(ParkingSpotStatus.REJECTED);

        ParkingSpot failed = createSpot();
        assertThat(failed.markReviewFailed(NOW.plusSeconds(1))).isTrue();
        assertThat(failed.status()).isEqualTo(ParkingSpotStatus.REVIEW_FAILED);
        assertThat(failed.isTerminal()).isTrue();
        assertThat(failed.applyAiValidationPassed(NOW.plusSeconds(2), POLICY)).isFalse();
        assertThat(failed.applyModeratorApproval(NOW.plusSeconds(3), POLICY)).isFalse();
        assertThat(failed.isVisibleForSearch(NOW.plusSeconds(4))).isFalse();
    }

    @Test
    void expiredSpotCanNeverBecomeApproved() {
        ParkingSpot spot = createActive();
        Instant afterExpiry = spot.expiresAt().plusSeconds(1);
        assertThat(spot.markExpired(afterExpiry)).isTrue();

        assertThat(spot.applyAiValidationPassed(afterExpiry.plusSeconds(1), POLICY)).isFalse();
        assertThat(spot.applyModeratorApproval(afterExpiry.plusSeconds(2), POLICY)).isFalse();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.EXPIRED);
    }

    @Test
    void validationRetriesAreBoundedThenTheSpotFailsTerminally() {
        ParkingSpot spot = createSpot();

        // Three attempts are permitted, each pushing the deadline further out.
        for (int attempt = 1; attempt <= 3; attempt++) {
            Instant overdue = spot.moderationDeadlineAt().plusSeconds(1);
            assertThat(spot.isModerationOverdue(overdue)).isTrue();
            assertThat(spot.recordValidationRetry(overdue, POLICY)).isTrue();
            assertThat(spot.moderationAttempts()).isEqualTo(attempt);
            assertThat(spot.moderationDeadlineAt()).isAfter(overdue);
        }

        Instant exhausted = spot.moderationDeadlineAt().plusSeconds(1);
        assertThat(spot.recordValidationRetry(exhausted, POLICY)).isFalse();
        assertThat(spot.markReviewFailed(exhausted)).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.REVIEW_FAILED);
    }

    @Test
    void staleVerdictsAreDetectedAgainstTheDecisionWatermark() {
        ParkingSpot spot = createSpot();
        Instant decidedAt = NOW.plusSeconds(60);
        assertThat(spot.applyAiValidationUncertain(decidedAt, POLICY)).isTrue();

        assertThat(spot.isStaleModerationEvent(decidedAt.minusSeconds(1))).isTrue();
        assertThat(spot.isStaleModerationEvent(decidedAt.plusSeconds(1))).isFalse();
        // Same-instant decisions are indistinguishable by time, so they pass the ordering
        // guard and are settled by the status guards instead.
        assertThat(spot.isStaleModerationEvent(decidedAt)).isFalse();
        // A missing timestamp must not be mistaken for a stale one.
        assertThat(spot.isStaleModerationEvent(null)).isFalse();
    }

    @Test
    void rejectsIllegalOrRiskyCreation() {
        assertThatThrownBy(() -> create(LegalStatus.ILLEGAL_OR_RISKY, Set.of(VehicleType.SEDAN), 41.0, 29.0))
                .isInstanceOf(ParkingException.class);
    }

    @Test
    void requiresAtLeastOneVehicleType() {
        assertThatThrownBy(() -> create(LegalStatus.LEGAL, Set.of(), 41.0, 29.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOutOfRangeCoordinates() {
        assertThatThrownBy(() -> create(LegalStatus.LEGAL, Set.of(VehicleType.ANY), 91.0, 29.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(LegalStatus.LEGAL, Set.of(VehicleType.ANY), 41.0, 181.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void illegalRiskVerificationMarksSuspiciousAndReducesConfidence() {
        ParkingSpot spot = createActive();

        spot.verify(UUID.randomUUID(), VerificationResult.ILLEGAL_OR_RISKY, NOW.plusSeconds(1));

        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.SUSPICIOUS);
        assertThat(spot.confidenceScore()).isEqualTo(0.6);
        assertThat(spot.isTerminal()).isFalse();
    }

    @Test
    void pendingValidationCannotBeVerifiedOrClaimed() {
        ParkingSpot spot = createSpot();

        assertThatThrownBy(() -> spot.verify(UUID.randomUUID(), VerificationResult.FILLED, NOW.plusSeconds(1)))
                .isInstanceOf(ParkingException.class);
        assertThatThrownBy(() -> spot.claim(UUID.randomUUID(), NOW.plusSeconds(1)))
                .isInstanceOf(ParkingException.class);
    }

    @Test
    void moderatorRejectionIsAuthoritativeAndIdempotentForTerminalState() {
        ParkingSpot spot = createActive();
        spot.verify(UUID.randomUUID(), VerificationResult.ILLEGAL_OR_RISKY, NOW.plusSeconds(1));

        assertThat(spot.markRejectedByModerator(NOW.plusSeconds(2))).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.REJECTED);
        assertThat(spot.markRejectedByModerator(NOW.plusSeconds(3))).isFalse();
    }

    @Test
    void applyAiValidationRejectedPersistsStructuredRejectionMetadata() {
        ParkingSpot spot = createSpot();
        ParkingSpotRejection rejection = ParkingSpotRejection.of(
                RejectionReasonCode.CLEARLY_UNRELATED_CONTENT,
                RejectionSource.AI_POLICY,
                NOW.plusSeconds(1),
                null,
                "2026-07-photo-policy-v3-recall");

        assertThat(spot.applyAiValidationRejected(NOW.plusSeconds(1), rejection)).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.REJECTED);
        assertThat(spot.rejection()).isNotNull();
        assertThat(spot.rejection().code()).isEqualTo(RejectionReasonCode.CLEARLY_UNRELATED_CONTENT);
        assertThat(spot.rejection().source()).isEqualTo(RejectionSource.AI_POLICY);
        assertThat(spot.rejection().policyVersion()).isEqualTo("2026-07-photo-policy-v3-recall");
    }

    @Test
    void systemMigrationRejectsActiveLegacySpotAndIsIdempotent() {
        ParkingSpot spot = createActive();
        ParkingSpotRejection rejection = ParkingSpotRejection.of(
                RejectionReasonCode.LEGACY_POLICY_RESET,
                RejectionSource.SYSTEM_MIGRATION,
                NOW.plusSeconds(2),
                null,
                "2026-07-photo-policy-v3-recall");

        assertThat(spot.markRejectedBySystemMigration(NOW.plusSeconds(2), rejection)).isTrue();
        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.REJECTED);
        assertThat(spot.rejection().code()).isEqualTo(RejectionReasonCode.LEGACY_POLICY_RESET);
        assertThat(spot.rejection().source()).isEqualTo(RejectionSource.SYSTEM_MIGRATION);
        assertThat(spot.markRejectedBySystemMigration(NOW.plusSeconds(3), rejection)).isFalse();
    }

    @Test
    void systemMigrationCanRejectEvenWhenLastAiPolicyIsCurrent_eligibilityIsJobConcern() {
        ParkingSpot spot = createActive();
        spot.recordLastAiPolicyVersion("2026-07-photo-policy-v3-recall");
        ParkingSpotRejection rejection = ParkingSpotRejection.of(
                RejectionReasonCode.LEGACY_POLICY_RESET,
                RejectionSource.SYSTEM_MIGRATION,
                NOW.plusSeconds(2),
                null,
                "2026-07-photo-policy-v3-recall");
        assertThat(spot.markRejectedBySystemMigration(NOW.plusSeconds(2), rejection)).isTrue();
        assertThat(spot.lastAiPolicyVersion()).isEqualTo("2026-07-photo-policy-v3-recall");
    }
}
