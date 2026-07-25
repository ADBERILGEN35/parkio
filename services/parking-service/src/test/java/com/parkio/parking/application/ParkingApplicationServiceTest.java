package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.command.CreateSpotCommand;
import com.parkio.parking.application.command.SearchNearbyQuery;
import com.parkio.parking.application.port.MediaAccessPort;
import com.parkio.parking.application.port.MediaReadinessPort;
import com.parkio.parking.application.port.ModerationMetricsPort;
import com.parkio.parking.application.port.OutboxEventAppender;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.application.port.ParkingSpotSearchLogRepository;
import com.parkio.parking.application.port.ParkingSpotStatusHistoryRepository;
import com.parkio.parking.application.port.ParkingSpotVerificationRepository;
import com.parkio.parking.application.port.ParkingSpotViewLogRepository;
import com.parkio.parking.application.result.SpotMediaAccess;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ModerationPolicy;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotSearchLog;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.domain.ParkingSpotVerification;
import com.parkio.parking.domain.ParkingSpotViewLog;
import com.parkio.parking.domain.ParkingSource;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.domain.VerificationResult;
import com.parkio.parking.domain.event.ParkingEvent;
import com.parkio.parking.domain.event.ParkingSpotActivatedEvent;
import com.parkio.parking.domain.event.ParkingSpotClaimedEvent;
import com.parkio.parking.domain.event.ParkingSpotCreatedEvent;
import com.parkio.parking.domain.event.ParkingSpotExpiredEvent;
import com.parkio.parking.domain.event.ParkingSpotMarkedFilledEvent;
import com.parkio.parking.domain.event.ParkingSpotModerationRetryRequestedEvent;
import com.parkio.parking.domain.event.ParkingSpotReviewFailedEvent;
import com.parkio.parking.domain.event.ParkingSpotVerifiedEvent;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural unit tests for {@link ParkingApplicationService} using in-memory fake
 * ports — no Spring context, no database, no PostGIS.
 */
class ParkingApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration VALIDATION_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration REVIEW_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration MAX_PUBLISHABLE_AGE = Duration.ofMinutes(30);
    private static final int MAX_VALIDATION_ATTEMPTS = 3;
    private static final ModerationPolicy POLICY = new ModerationPolicy(
            TTL, VALIDATION_TIMEOUT, Duration.ofMinutes(1), MAX_VALIDATION_ATTEMPTS,
            REVIEW_TIMEOUT, MAX_PUBLISHABLE_AGE);

    private FakeParkingSpotRepository spots;
    private FakeVerificationRepository verifications;
    private FakeStatusHistoryRepository statusHistory;
    private FakeViewLogRepository viewLogs;
    private FakeSearchLogRepository searchLogs;
    private FakeOutboxEventAppender outbox;
    private FakeMediaAccessPort mediaAccess;
    private FakeMediaReadinessPort mediaReadiness;
    private ParkingSessionService parkingSessions;
    private MutableClock clock;
    private RecordingModerationMetrics moderationMetrics;
    private ParkingApplicationService service;

    @BeforeEach
    void setUp() {
        spots = new FakeParkingSpotRepository();
        verifications = new FakeVerificationRepository();
        statusHistory = new FakeStatusHistoryRepository();
        viewLogs = new FakeViewLogRepository();
        searchLogs = new FakeSearchLogRepository();
        outbox = new FakeOutboxEventAppender();
        mediaAccess = new FakeMediaAccessPort();
        mediaReadiness = new FakeMediaReadinessPort();
        parkingSessions = mock(ParkingSessionService.class);
        clock = new MutableClock(NOW);
        moderationMetrics = new RecordingModerationMetrics();
        when(parkingSessions.startSession(any(), any(), anyDouble(), anyDouble(), any(), any()))
                .thenAnswer(invocation -> ParkingSession.start(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4),
                        invocation.getArgument(5),
                        NOW));
        service = new ParkingApplicationService(spots, verifications, statusHistory, viewLogs, searchLogs,
                outbox, mediaAccess, mediaReadiness, new ParkingSearchSettings(1000, 10, 50000, 50),
                parkingSessions, POLICY, moderationMetrics, clock);
    }

    private CreateSpotCommand createCommand(UUID owner, LegalStatus legalStatus) {
        return new CreateSpotCommand(owner, UUID.randomUUID(), 41.0082, 28.9784, "Main St", "Nice spot",
                false, Set.of(VehicleType.SEDAN), ParkingContext.STREET_PARKING, legalStatus, Set.of());
    }

    /** Creates a spot then applies AI PASSED so verify/claim tests exercise ACTIVE lifecycle. */
    private ParkingSpot createPublishedSpot(UUID owner) {
        ParkingSpot spot = service.createSpot(createCommand(owner, LegalStatus.LEGAL));
        service.applyAiValidationResult(spot.id(), "PASSED", List.of(), UUID.randomUUID(), clock.instant());
        return spots.byId.get(spot.id());
    }

    @Test
    void createsLegalSpotAsPendingValidationAndEmitsEvent() {
        UUID owner = UUID.randomUUID();

        ParkingSpot spot = service.createSpot(createCommand(owner, LegalStatus.LEGAL));

        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.PENDING_VALIDATION);
        // The advertised lifetime has not started yet — it begins at publication, not here.
        assertThat(spot.activatedAt()).isNull();
        assertThat(spot.expiresAt()).isNull();
        assertThat(spot.moderationDeadlineAt()).isEqualTo(NOW.plus(VALIDATION_TIMEOUT));
        assertThat(spots.byId).containsKey(spot.id());
        assertThat(statusHistory.all).singleElement()
                .satisfies(h -> assertThat(h.newStatus()).isEqualTo(ParkingSpotStatus.PENDING_VALIDATION));
        assertThat(outbox.events).singleElement().isInstanceOf(ParkingSpotCreatedEvent.class)
                .satisfies(e -> assertThat(((ParkingSpotCreatedEvent) e).status())
                        .isEqualTo(ParkingSpotStatus.PENDING_VALIDATION));
    }

    @Test
    void applyAiValidationPassedActivatesAndEmitsActivatedEvent() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        outbox.events.clear();
        statusHistory.all.clear();

        service.applyAiValidationResult(spot.id(), "PASSED", List.of(), UUID.randomUUID(), clock.instant());

        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(statusHistory.all).singleElement()
                .satisfies(h -> {
                    assertThat(h.previousStatus()).isEqualTo(ParkingSpotStatus.PENDING_VALIDATION);
                    assertThat(h.newStatus()).isEqualTo(ParkingSpotStatus.ACTIVE);
                    assertThat(h.reason()).isEqualTo("AI_PASSED");
                });
        assertThat(outbox.events).singleElement().isInstanceOf(ParkingSpotActivatedEvent.class);
    }

    @Test
    void applyAiValidationWarningMovesToPendingReviewWithoutActivationEvent() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        outbox.events.clear();

        service.applyAiValidationResult(spot.id(), "WARNING", List.of(), UUID.randomUUID(), clock.instant());

        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.PENDING_REVIEW);
        assertThat(outbox.events).isEmpty();
        assertThat(statusHistory.all.get(statusHistory.all.size() - 1).reason()).isEqualTo("AI_PENDING_REVIEW");
    }

    @Test
    void applyAiValidationFailedOrNotParkingRejectsSpot() {
        ParkingSpot failed = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        service.applyAiValidationResult(failed.id(), "FAILED", List.of(), UUID.randomUUID(), clock.instant());
        assertThat(spots.byId.get(failed.id()).status()).isEqualTo(ParkingSpotStatus.REJECTED);

        ParkingSpot notParking = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        service.applyAiValidationResult(notParking.id(), "PASSED", List.of("NOT_A_PARKING_SPOT"), UUID.randomUUID(), clock.instant());
        assertThat(spots.byId.get(notParking.id()).status()).isEqualTo(ParkingSpotStatus.REJECTED);
    }

    @Test
    void applyAiValidationUnknownStatusIsFailClosed() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        outbox.events.clear();
        int historyBefore = statusHistory.all.size();

        service.applyAiValidationResult(spot.id(), "BOGUS", List.of(), UUID.randomUUID(), clock.instant());

        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.PENDING_VALIDATION);
        assertThat(statusHistory.all).hasSize(historyBefore);
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void rejectsIllegalOrRiskySpotCreation() {
        UUID owner = UUID.randomUUID();

        assertThatThrownBy(() -> service.createSpot(createCommand(owner, LegalStatus.ILLEGAL_OR_RISKY)))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.ILLEGAL_SPOT_REJECTED);

        assertThat(spots.byId).isEmpty();
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void createSpotChecksReferencedMediaIsReady() {
        UUID owner = UUID.randomUUID();
        CreateSpotCommand command = createCommand(owner, LegalStatus.LEGAL);

        service.createSpot(command);

        assertThat(mediaReadiness.checkCount).isEqualTo(1);
        assertThat(mediaReadiness.lastMediaId).isEqualTo(command.mediaId());
        assertThat(mediaReadiness.lastOwnerUserId).isEqualTo(owner);
    }

    @Test
    void rejectsSpotCreationWhenMediaNotReady() {
        UUID owner = UUID.randomUUID();
        mediaReadiness.toThrow = new ParkingException(ParkingErrorCode.MEDIA_NOT_READY);

        assertThatThrownBy(() -> service.createSpot(createCommand(owner, LegalStatus.LEGAL)))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.MEDIA_NOT_READY);

        // Fail closed: no spot persisted, no event emitted.
        assertThat(spots.byId).isEmpty();
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void rejectsSpotCreationWhenMediaBelongsToAnotherUser() {
        UUID owner = UUID.randomUUID();
        mediaReadiness.toThrow = new ParkingException(ParkingErrorCode.MEDIA_NOT_READY);

        assertThatThrownBy(() -> service.createSpot(createCommand(owner, LegalStatus.LEGAL)))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.MEDIA_NOT_READY);

        assertThat(spots.byId).isEmpty();
        assertThat(outbox.events).isEmpty();
        assertThat(mediaReadiness.lastOwnerUserId).isEqualTo(owner);
    }

    @Test
    void failsClosedWhenMediaServiceUnavailableDuringCreation() {
        UUID owner = UUID.randomUUID();
        mediaReadiness.toThrow = new ParkingException(ParkingErrorCode.MEDIA_ACCESS_UNAVAILABLE);

        assertThatThrownBy(() -> service.createSpot(createCommand(owner, LegalStatus.LEGAL)))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.MEDIA_ACCESS_UNAVAILABLE);

        assertThat(spots.byId).isEmpty();
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void ownerCanReadHiddenSpotDetail() {
        UUID owner = UUID.randomUUID();
        ParkingSpot filled = buildSpot(owner, ParkingSpotStatus.FILLED, NOW.plus(5, ChronoUnit.MINUTES),
                LegalStatus.LEGAL);
        spots.save(filled);

        ParkingSpot result = service.getSpotForViewer(filled.id(), owner, false);

        assertThat(result.id()).isEqualTo(filled.id());
        assertThat(viewLogs.all).singleElement()
                .satisfies(log -> assertThat(log.viewerUserId()).isEqualTo(owner));
    }

    @Test
    void moderatorCanReadHiddenSpotDetail() {
        UUID moderator = UUID.randomUUID();
        ParkingSpot filled = buildSpot(UUID.randomUUID(), ParkingSpotStatus.FILLED,
                NOW.plus(5, ChronoUnit.MINUTES), LegalStatus.LEGAL);
        spots.save(filled);

        ParkingSpot result = service.getSpotForViewer(filled.id(), moderator, true);

        assertThat(result.id()).isEqualTo(filled.id());
    }

    @Test
    void adminCanReadHiddenSpotDetail() {
        UUID admin = UUID.randomUUID();
        ParkingSpot rejected = buildSpot(UUID.randomUUID(), ParkingSpotStatus.REJECTED,
                NOW.plus(5, ChronoUnit.MINUTES), LegalStatus.LEGAL);
        spots.save(rejected);

        ParkingSpot result = service.getSpotForViewer(rejected.id(), admin, true);

        assertThat(result.id()).isEqualTo(rejected.id());
    }

    @Test
    void nonOwnerCanReadVisibleSpotDetail() {
        ParkingSpot visible = buildSpot(UUID.randomUUID(), ParkingSpotStatus.ACTIVE,
                NOW.plus(5, ChronoUnit.MINUTES), LegalStatus.LEGAL);
        spots.save(visible);

        ParkingSpot result = service.getSpotForViewer(visible.id(), UUID.randomUUID(), false);

        assertThat(result.id()).isEqualTo(visible.id());
    }

    @Test
    void nonOwnerCannotReadHiddenSpotDetail() {
        ParkingSpot suspicious = buildSpot(UUID.randomUUID(), ParkingSpotStatus.SUSPICIOUS,
                NOW.plus(5, ChronoUnit.MINUTES), LegalStatus.LEGAL);
        spots.save(suspicious);

        assertSpotDetailNotFound(suspicious.id(), UUID.randomUUID());
        assertThat(viewLogs.all).isEmpty();
    }

    @Test
    void nonOwnerCannotReadExpiredSpotDetail() {
        ParkingSpot expired = buildSpot(UUID.randomUUID(), ParkingSpotStatus.ACTIVE,
                NOW.minus(1, ChronoUnit.MINUTES), LegalStatus.LEGAL);
        spots.save(expired);

        assertSpotDetailNotFound(expired.id(), UUID.randomUUID());
        assertThat(spots.byId.get(expired.id()).status()).isEqualTo(ParkingSpotStatus.EXPIRED);
        assertThat(viewLogs.all).isEmpty();
    }

    @Test
    void nonOwnerCannotReadRejectedSpotDetail() {
        ParkingSpot rejected = buildSpot(UUID.randomUUID(), ParkingSpotStatus.REJECTED,
                NOW.plus(5, ChronoUnit.MINUTES), LegalStatus.LEGAL);
        spots.save(rejected);

        assertSpotDetailNotFound(rejected.id(), UUID.randomUUID());
        assertThat(viewLogs.all).isEmpty();
    }

    @Test
    void nonOwnerCannotVerifyHiddenSuspiciousSpotAsAvailable() {
        ParkingSpot suspicious = buildSpot(UUID.randomUUID(), ParkingSpotStatus.SUSPICIOUS,
                NOW.plus(5, ChronoUnit.MINUTES), LegalStatus.LEGAL);
        spots.save(suspicious);

        assertThatThrownBy(() -> service.verifySpot(suspicious.id(), UUID.randomUUID(), VerificationResult.AVAILABLE))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.SPOT_NOT_FOUND);
    }

    @Test
    void ownerCannotVerifyOwnSpot() {
        UUID owner = UUID.randomUUID();
        ParkingSpot spot = createPublishedSpot(owner);

        assertThatThrownBy(() -> service.verifySpot(spot.id(), owner, VerificationResult.AVAILABLE))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.OWNER_CANNOT_VERIFY);
    }

    @Test
    void duplicateVerificationBySameUserIsRejected() {
        UUID owner = UUID.randomUUID();
        UUID verifier = UUID.randomUUID();
        ParkingSpot spot = createPublishedSpot(owner);

        service.verifySpot(spot.id(), verifier, VerificationResult.AVAILABLE);

        assertThatThrownBy(() -> service.verifySpot(spot.id(), verifier, VerificationResult.AVAILABLE))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.ALREADY_VERIFIED);
    }

    @Test
    void availableVerificationsVerifyAndExtendExpiration() {
        UUID owner = UUID.randomUUID();
        ParkingSpot spot = createPublishedSpot(owner);

        ParkingSpot afterFirst = service.verifySpot(spot.id(), UUID.randomUUID(), VerificationResult.AVAILABLE);
        assertThat(afterFirst.status()).isEqualTo(ParkingSpotStatus.VERIFIED);
        assertThat(afterFirst.verificationCount()).isEqualTo(1);
        assertThat(afterFirst.expiresAt()).isEqualTo(NOW.plus(15, ChronoUnit.MINUTES));

        ParkingSpot afterSecond = service.verifySpot(spot.id(), UUID.randomUUID(), VerificationResult.AVAILABLE);
        assertThat(afterSecond.verificationCount()).isEqualTo(2);
        assertThat(afterSecond.expiresAt()).isEqualTo(NOW.plus(20, ChronoUnit.MINUTES));

        assertThat(outbox.events).filteredOn(e -> e instanceof ParkingSpotVerifiedEvent).hasSize(2);
    }

    @Test
    void illegalRiskVerificationIsSuspiciousAndEmitsNoRejectionEvent() {
        ParkingSpot spot = createPublishedSpot(UUID.randomUUID());
        outbox.events.clear();

        ParkingSpot reported =
                service.verifySpot(spot.id(), UUID.randomUUID(), VerificationResult.ILLEGAL_OR_RISKY);

        assertThat(reported.status()).isEqualTo(ParkingSpotStatus.SUSPICIOUS);
        assertThat(reported.confidenceScore()).isEqualTo(0.6);
        assertThat(outbox.events).singleElement()
                .isInstanceOf(ParkingSpotVerifiedEvent.class)
                .satisfies(event -> assertThat(((ParkingSpotVerifiedEvent) event).result())
                        .isEqualTo(VerificationResult.ILLEGAL_OR_RISKY));
        assertThat(outbox.events).noneMatch(event -> event.eventType().equals("ParkingSpotRejected"));
    }

    @Test
    void moderatorRejectionUpdatesStatusAndHistoryWithoutEmittingParkingEvent() {
        ParkingSpot spot = createPublishedSpot(UUID.randomUUID());
        service.verifySpot(spot.id(), UUID.randomUUID(), VerificationResult.ILLEGAL_OR_RISKY);
        outbox.events.clear();
        statusHistory.all.clear();

        service.rejectSpotByModerator(spot.id(), UUID.randomUUID(), clock.instant());

        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.REJECTED);
        assertThat(statusHistory.all).singleElement()
                .satisfies(history -> {
                    assertThat(history.previousStatus()).isEqualTo(ParkingSpotStatus.SUSPICIOUS);
                    assertThat(history.newStatus()).isEqualTo(ParkingSpotStatus.REJECTED);
                    assertThat(history.reason()).isEqualTo("MODERATOR_REJECTED");
                });
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void filledReportsMoveSpotToSuspiciousThenFilled() {
        UUID owner = UUID.randomUUID();
        ParkingSpot spot = createPublishedSpot(owner);

        ParkingSpot afterOne = service.verifySpot(spot.id(), UUID.randomUUID(), VerificationResult.FILLED);
        assertThat(afterOne.status()).isEqualTo(ParkingSpotStatus.SUSPICIOUS);
        assertThat(afterOne.filledReportCount()).isEqualTo(1);

        ParkingSpot afterTwo = service.verifySpot(spot.id(), UUID.randomUUID(), VerificationResult.FILLED);
        assertThat(afterTwo.status()).isEqualTo(ParkingSpotStatus.FILLED);
        assertThat(afterTwo.filledReportCount()).isEqualTo(2);

        assertThat(outbox.events).filteredOn(e -> e instanceof ParkingSpotMarkedFilledEvent).hasSize(1);
    }

    @Test
    void claimMarksSpotFilledAndEmitsEvent() {
        UUID owner = UUID.randomUUID();
        UUID claimer = UUID.randomUUID();
        ParkingSpot spot = createPublishedSpot(owner);

        ParkingSpot claimed = service.claimSpot(spot.id(), claimer);

        assertThat(claimed.status()).isEqualTo(ParkingSpotStatus.FILLED);
        assertThat(outbox.events).filteredOn(e -> e instanceof ParkingSpotClaimedEvent).hasSize(1);
        verify(parkingSessions).startSession(
                eq(claimer),
                eq(ParkingSource.COMMUNITY),
                eq(spot.latitude()),
                eq(spot.longitude()),
                isNull(),
                isNull());
    }

    @Test
    void verifiedSpotCreatesCommunitySessionFromServerOwnedFields() {
        UUID owner = UUID.randomUUID();
        UUID claimer = UUID.randomUUID();
        ParkingSpot spot = createPublishedSpot(owner);
        service.verifySpot(spot.id(), UUID.randomUUID(), VerificationResult.AVAILABLE);

        service.claimSpot(spot.id(), claimer);

        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.FILLED);
        verify(parkingSessions).startSession(
                eq(claimer),
                eq(ParkingSource.COMMUNITY),
                eq(spot.latitude()),
                eq(spot.longitude()),
                isNull(),
                isNull());
    }

    @Test
    void ownerCannotClaimOwnSpot() {
        UUID owner = UUID.randomUUID();
        ParkingSpot spot = createPublishedSpot(owner);

        assertThatThrownBy(() -> service.claimSpot(spot.id(), owner))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.OWNER_CANNOT_CLAIM);
        verifyNoInteractions(parkingSessions);
    }

    @Test
    void expiredSpotDoesNotStartCommunitySession() {
        ParkingSpot spot = createPublishedSpot(UUID.randomUUID());
        clock.set(NOW.plus(11, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> service.claimSpot(spot.id(), UUID.randomUUID()))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.SPOT_EXPIRED);

        verifyNoInteractions(parkingSessions);
    }

    @Test
    void terminalSpotDoesNotStartCommunitySession() {
        ParkingSpot filled = buildSpot(
                UUID.randomUUID(), ParkingSpotStatus.FILLED, NOW.plus(5, ChronoUnit.MINUTES), LegalStatus.LEGAL);
        spots.save(filled);

        assertThatThrownBy(() -> service.claimSpot(filled.id(), UUID.randomUUID()))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.SPOT_NOT_CLAIMABLE);

        verifyNoInteractions(parkingSessions);
    }

    @Test
    void activeSessionConflictPreventsClaimPersistenceSideEffects() {
        UUID claimer = UUID.randomUUID();
        ParkingSpot spot = createPublishedSpot(UUID.randomUUID());
        int historyBefore = statusHistory.all.size();
        int eventsBefore = outbox.events.size();
        doThrow(new ParkingException(ParkingErrorCode.ACTIVE_PARKING_SESSION_EXISTS))
                .when(parkingSessions)
                .startSession(
                        eq(claimer), eq(ParkingSource.COMMUNITY), anyDouble(), anyDouble(), isNull(), isNull());

        assertThatThrownBy(() -> service.claimSpot(spot.id(), claimer))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.ACTIVE_PARKING_SESSION_EXISTS);

        assertThat(statusHistory.all).hasSize(historyBefore);
        assertThat(outbox.events).hasSize(eventsBefore);
    }

    @Test
    void expiredSpotCannotBeVerified() {
        UUID owner = UUID.randomUUID();
        ParkingSpot spot = createPublishedSpot(owner);

        clock.set(NOW.plus(11, ChronoUnit.MINUTES)); // past the 10-minute window

        assertThatThrownBy(() -> service.verifySpot(spot.id(), UUID.randomUUID(), VerificationResult.AVAILABLE))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.SPOT_EXPIRED);

        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.EXPIRED);
        assertThat(outbox.events).filteredOn(e -> e instanceof ParkingSpotExpiredEvent).hasSize(1);
    }

    @Test
    void expiryBatchExpiresEligibleSpotsAndEmitsHistoryAndEvents() {
        Instant past = NOW.minus(1, ChronoUnit.MINUTES);
        UUID owner = UUID.randomUUID();
        ParkingSpot active = buildSpot(owner, ParkingSpotStatus.ACTIVE, past, LegalStatus.LEGAL);
        ParkingSpot verified = buildSpot(owner, ParkingSpotStatus.VERIFIED, past, LegalStatus.LEGAL);
        ParkingSpot suspicious = buildSpot(owner, ParkingSpotStatus.SUSPICIOUS, past, LegalStatus.LEGAL);
        List.of(active, verified, suspicious).forEach(spots::save);

        int expired = service.expireElapsedSpots(10);

        assertThat(expired).isEqualTo(3);
        assertThat(List.of(active, verified, suspicious))
                .extracting(ParkingSpot::status)
                .containsOnly(ParkingSpotStatus.EXPIRED);
        assertThat(statusHistory.all).hasSize(3)
                .allSatisfy(history -> {
                    assertThat(history.newStatus()).isEqualTo(ParkingSpotStatus.EXPIRED);
                    assertThat(history.reason()).isEqualTo("EXPIRED");
                });
        assertThat(outbox.events).hasSize(3)
                .allSatisfy(event -> assertThat(event).isInstanceOf(ParkingSpotExpiredEvent.class));
    }

    @Test
    void expiryBatchSkipsTerminalAndFutureSpots() {
        Instant past = NOW.minus(1, ChronoUnit.MINUTES);
        Instant future = NOW.plus(1, ChronoUnit.MINUTES);
        UUID owner = UUID.randomUUID();
        ParkingSpot expired = buildSpot(owner, ParkingSpotStatus.EXPIRED, past, LegalStatus.LEGAL);
        ParkingSpot filled = buildSpot(owner, ParkingSpotStatus.FILLED, past, LegalStatus.LEGAL);
        ParkingSpot rejected = buildSpot(owner, ParkingSpotStatus.REJECTED, past, LegalStatus.LEGAL);
        ParkingSpot active = buildSpot(owner, ParkingSpotStatus.ACTIVE, future, LegalStatus.LEGAL);
        List.of(expired, filled, rejected, active).forEach(spots::save);

        assertThat(service.expireElapsedSpots(10)).isZero();
        assertThat(statusHistory.all).isEmpty();
        assertThat(outbox.events).isEmpty();
    }

    // --- Moderation lifetime rule ----------------------------------------
    //
    // The defect these cover: `expiresAt` used to be stamped at creation and never
    // recomputed, so a spot's user-visible lifetime was consumed while it waited on
    // moderation — and an owner merely opening their own pending spot expired it.

    @Test
    void pendingSpotIsNotExpiredWhenTheOwnerOpensItLongAfterSubmission() {
        UUID owner = UUID.randomUUID();
        ParkingSpot spot = service.createSpot(createCommand(owner, LegalStatus.LEGAL));
        outbox.events.clear();
        // Past the old ten-minute window, still within max-publishable-age, no verdict yet.
        clock.set(NOW.plus(Duration.ofMinutes(20)));

        ParkingSpot reloaded = service.getMySpot(owner, spot.id());

        assertThat(reloaded.status()).isEqualTo(ParkingSpotStatus.PENDING_VALIDATION);
        assertThat(outbox.events).noneMatch(e -> e instanceof ParkingSpotExpiredEvent);
        assertThat(moderationMetrics.expiredBeforeApproved).isZero();
    }

    @Test
    void expiryBatchIgnoresPendingSpots() {
        UUID owner = UUID.randomUUID();
        service.createSpot(createCommand(owner, LegalStatus.LEGAL));
        ParkingSpot inReview = service.createSpot(createCommand(owner, LegalStatus.LEGAL));
        service.applyAiValidationResult(inReview.id(), "WARNING", List.of(), UUID.randomUUID(), clock.instant());
        statusHistory.all.clear();
        outbox.events.clear();
        clock.set(NOW.plus(Duration.ofMinutes(25)));

        assertThat(service.expireElapsedSpots(10)).isZero();
        assertThat(spots.byId.values()).extracting(ParkingSpot::status)
                .containsOnly(ParkingSpotStatus.PENDING_VALIDATION, ParkingSpotStatus.PENDING_REVIEW);
        assertThat(outbox.events).isEmpty();
        assertThat(moderationMetrics.expiredBeforeApproved).isZero();
    }

    @Test
    void delayedApprovalWithinMaxPublishableAgeGrantsTheFullAdvertisedLifetime() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        Instant approvedAt = NOW.plus(Duration.ofMinutes(20));
        clock.set(approvedAt);

        service.applyAiValidationResult(spot.id(), "PASSED", List.of(), UUID.randomUUID(), approvedAt);

        ParkingSpot published = spots.byId.get(spot.id());
        assertThat(published.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(published.activatedAt()).isEqualTo(approvedAt);
        assertThat(published.expiresAt()).isEqualTo(approvedAt.plus(TTL));
        assertThat(published.isVisibleForSearch(approvedAt)).isTrue();
    }

    @Test
    void approvalPastMaxPublishableAgeFailsAsStaleAndDoesNotPublish() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        Instant approvedAt = NOW.plus(Duration.ofMinutes(31));
        clock.set(approvedAt);
        outbox.events.clear();
        statusHistory.all.clear();

        service.applyAiValidationResult(spot.id(), "PASSED", List.of(), UUID.randomUUID(), approvedAt);

        ParkingSpot failed = spots.byId.get(spot.id());
        assertThat(failed.status()).isEqualTo(ParkingSpotStatus.REVIEW_FAILED);
        assertThat(failed.activatedAt()).isNull();
        assertThat(failed.expiresAt()).isNull();
        assertThat(outbox.events).singleElement().isInstanceOf(ParkingSpotReviewFailedEvent.class)
                .satisfies(e -> assertThat(((ParkingSpotReviewFailedEvent) e).reason())
                        .isEqualTo(ParkingSpotReviewFailedEvent.REASON_STALE_BEFORE_PUBLICATION));
        assertThat(moderationMetrics.failures)
                .containsExactly(ParkingSpotReviewFailedEvent.REASON_STALE_BEFORE_PUBLICATION);
    }

    @Test
    void moderatorApprovalPublishesPendingReviewSpotAndEmitsActivatedEvent() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        service.applyAiValidationResult(spot.id(), "WARNING", List.of(), UUID.randomUUID(), clock.instant());
        outbox.events.clear();
        statusHistory.all.clear();
        Instant approvedAt = NOW.plus(Duration.ofMinutes(12));
        clock.set(approvedAt);

        service.approveSpotByModerator(spot.id(), UUID.randomUUID(), approvedAt);

        ParkingSpot published = spots.byId.get(spot.id());
        assertThat(published.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(published.expiresAt()).isEqualTo(approvedAt.plus(TTL));
        assertThat(statusHistory.all).singleElement().satisfies(h -> {
            assertThat(h.previousStatus()).isEqualTo(ParkingSpotStatus.PENDING_REVIEW);
            assertThat(h.newStatus()).isEqualTo(ParkingSpotStatus.ACTIVE);
            assertThat(h.reason()).isEqualTo("MODERATOR_APPROVED");
        });
        assertThat(outbox.events).singleElement().isInstanceOf(ParkingSpotActivatedEvent.class);
    }

    @Test
    void duplicateApprovalEventsAreIdempotentAndDoNotExtendLifetime() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        Instant approvedAt = NOW.plus(Duration.ofMinutes(1));
        clock.set(approvedAt);
        service.applyAiValidationResult(spot.id(), "PASSED", List.of(), UUID.randomUUID(), approvedAt);
        Instant firstExpiry = spots.byId.get(spot.id()).expiresAt();
        outbox.events.clear();
        statusHistory.all.clear();

        // A redelivered verdict, and a moderator approval arriving after the fact.
        clock.set(approvedAt.plus(Duration.ofMinutes(3)));
        service.applyAiValidationResult(spot.id(), "PASSED", List.of(), UUID.randomUUID(), clock.instant());
        service.approveSpotByModerator(spot.id(), UUID.randomUUID(), clock.instant());

        assertThat(spots.byId.get(spot.id()).expiresAt()).isEqualTo(firstExpiry);
        assertThat(outbox.events).isEmpty();
        assertThat(statusHistory.all).isEmpty();
    }

    @Test
    void staleVerdictCannotOverwriteANewerLifecycleState() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        Instant approvedAt = NOW.plus(Duration.ofMinutes(10));
        clock.set(approvedAt);
        service.approveSpotByModerator(spot.id(), UUID.randomUUID(), approvedAt);
        outbox.events.clear();
        statusHistory.all.clear();

        // An AI rejection produced *before* the approval arrives late and out of order.
        clock.set(approvedAt.plus(Duration.ofMinutes(1)));
        service.applyAiValidationResult(spot.id(), "FAILED", List.of(),
                UUID.randomUUID(), approvedAt.minus(Duration.ofMinutes(5)));

        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(statusHistory.all).isEmpty();
    }

    @Test
    void overdueValidationIsRetriedThroughTheOutboxUpToTheBound() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        outbox.events.clear();

        for (int attempt = 1; attempt <= MAX_VALIDATION_ATTEMPTS; attempt++) {
            clock.set(spots.byId.get(spot.id()).moderationDeadlineAt().plusSeconds(1));
            assertThat(service.processModerationTimeouts(10)).isEqualTo(1);
            assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.PENDING_VALIDATION);
            assertThat(spots.byId.get(spot.id()).moderationAttempts()).isEqualTo(attempt);
        }

        assertThat(outbox.events).hasSize(MAX_VALIDATION_ATTEMPTS)
                .allSatisfy(e -> assertThat(e).isInstanceOf(ParkingSpotModerationRetryRequestedEvent.class));
        assertThat(moderationMetrics.retries).containsExactly(1, 2, 3);
    }

    @Test
    void retriedSpotThatEventuallyPassesStillGetsTheFullLifetime() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        clock.set(spots.byId.get(spot.id()).moderationDeadlineAt().plusSeconds(1));
        assertThat(service.processModerationTimeouts(10)).isEqualTo(1);

        Instant passedAt = clock.instant().plus(Duration.ofMinutes(1));
        clock.set(passedAt);
        service.applyAiValidationResult(spot.id(), "PASSED", List.of(), UUID.randomUUID(), passedAt);

        ParkingSpot published = spots.byId.get(spot.id());
        assertThat(published.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(published.expiresAt()).isEqualTo(passedAt.plus(TTL));
    }

    @Test
    void retryExhaustionProducesTerminalReviewFailure() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        for (int attempt = 1; attempt <= MAX_VALIDATION_ATTEMPTS; attempt++) {
            clock.set(spots.byId.get(spot.id()).moderationDeadlineAt().plusSeconds(1));
            service.processModerationTimeouts(10);
        }
        outbox.events.clear();
        statusHistory.all.clear();

        clock.set(spots.byId.get(spot.id()).moderationDeadlineAt().plusSeconds(1));
        assertThat(service.processModerationTimeouts(10)).isEqualTo(1);

        ParkingSpot failed = spots.byId.get(spot.id());
        assertThat(failed.status()).isEqualTo(ParkingSpotStatus.REVIEW_FAILED);
        assertThat(failed.isTerminal()).isTrue();
        assertThat(statusHistory.all).singleElement().satisfies(h ->
                assertThat(h.reason()).isEqualTo(ParkingSpotReviewFailedEvent.REASON_RETRIES_EXHAUSTED));
        assertThat(outbox.events).singleElement().isInstanceOf(ParkingSpotReviewFailedEvent.class);
        assertThat(moderationMetrics.failures)
                .containsExactly(ParkingSpotReviewFailedEvent.REASON_RETRIES_EXHAUSTED);
    }

    @Test
    void humanReviewTimeoutFailsTerminallyWithoutRetrying() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        service.applyAiValidationResult(spot.id(), "WARNING", List.of(), UUID.randomUUID(), clock.instant());
        outbox.events.clear();
        statusHistory.all.clear();
        clock.set(NOW.plus(REVIEW_TIMEOUT).plusSeconds(1));

        assertThat(service.processModerationTimeouts(10)).isEqualTo(1);

        ParkingSpot failed = spots.byId.get(spot.id());
        assertThat(failed.status()).isEqualTo(ParkingSpotStatus.REVIEW_FAILED);
        assertThat(failed.moderationAttempts()).isZero();
        assertThat(statusHistory.all).singleElement().satisfies(h -> {
            assertThat(h.previousStatus()).isEqualTo(ParkingSpotStatus.PENDING_REVIEW);
            assertThat(h.reason()).isEqualTo(ParkingSpotReviewFailedEvent.REASON_REVIEW_TIMEOUT);
        });
        assertThat(outbox.events).singleElement().isInstanceOf(ParkingSpotReviewFailedEvent.class);
        assertThat(moderationMetrics.timeouts).containsExactly(ParkingSpotStatus.PENDING_REVIEW);
    }

    @Test
    void reviewFailedSpotCanNeverBecomeVisibleAfterwards() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        service.applyAiValidationResult(spot.id(), "WARNING", List.of(), UUID.randomUUID(), clock.instant());
        clock.set(NOW.plus(REVIEW_TIMEOUT).plusSeconds(1));
        service.processModerationTimeouts(10);
        outbox.events.clear();

        Instant later = clock.instant().plusSeconds(60);
        clock.set(later);
        service.approveSpotByModerator(spot.id(), UUID.randomUUID(), later);
        service.applyAiValidationResult(spot.id(), "PASSED", List.of(), UUID.randomUUID(), later);

        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.REVIEW_FAILED);
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void moderationTimeoutJobLeavesAlreadyPublishedSpotsAlone() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        service.applyAiValidationResult(spot.id(), "PASSED", List.of(), UUID.randomUUID(), clock.instant());
        outbox.events.clear();
        clock.set(NOW.plus(Duration.ofDays(3)));

        assertThat(service.processModerationTimeouts(10)).isZero();
        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void nearbySearchFiltersExpiredFilledRejectedAndIllegal() {
        double lat = 41.0;
        double lng = 29.0;
        UUID owner = UUID.randomUUID();
        Instant future = NOW.plus(5, ChronoUnit.MINUTES);
        Instant past = NOW.minus(1, ChronoUnit.MINUTES);

        ParkingSpot active = buildSpot(owner, ParkingSpotStatus.ACTIVE, future, LegalStatus.LEGAL);
        ParkingSpot verified = buildSpot(owner, ParkingSpotStatus.VERIFIED, future, LegalStatus.LEGAL);
        ParkingSpot expired = buildSpot(owner, ParkingSpotStatus.ACTIVE, past, LegalStatus.LEGAL);
        ParkingSpot filled = buildSpot(owner, ParkingSpotStatus.FILLED, future, LegalStatus.LEGAL);
        ParkingSpot rejected = buildSpot(owner, ParkingSpotStatus.REJECTED, future, LegalStatus.LEGAL);
        ParkingSpot illegal = buildSpot(owner, ParkingSpotStatus.ACTIVE, future, LegalStatus.ILLEGAL_OR_RISKY);
        List.of(active, verified, expired, filled, rejected, illegal).forEach(spots::save);

        List<ParkingSpot> results = service.searchNearby(
                new SearchNearbyQuery(UUID.randomUUID(), lat, lng, null, null));

        assertThat(results).extracting(ParkingSpot::id)
                .containsExactlyInAnyOrder(active.id(), verified.id());
        assertThat(searchLogs.all).singleElement()
                .satisfies(s -> assertThat(s.resultCount()).isEqualTo(2));
    }

    @Test
    void nearbySearchRejectsNonPositiveRadius() {
        assertThatThrownBy(() -> service.searchNearby(
                new SearchNearbyQuery(UUID.randomUUID(), 41.0, 29.0, 0.0, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.searchNearby(
                new SearchNearbyQuery(UUID.randomUUID(), 41.0, 29.0, -5.0, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nearbySearchRejectsRadiusAboveMax() {
        assertThatThrownBy(() -> service.searchNearby(
                new SearchNearbyQuery(UUID.randomUUID(), 41.0, 29.0, 50_001.0, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nearbySearchRejectsInvalidLimit() {
        assertThatThrownBy(() -> service.searchNearby(
                new SearchNearbyQuery(UUID.randomUUID(), 41.0, 29.0, null, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.searchNearby(
                new SearchNearbyQuery(UUID.randomUUID(), 41.0, 29.0, null, 51)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nearbySearchAcceptsInBoundsInputs() {
        // Within bounds and no spots stored → empty result, no exception.
        List<ParkingSpot> results = service.searchNearby(
                new SearchNearbyQuery(UUID.randomUUID(), 41.0, 29.0, 2000.0, 5));

        assertThat(results).isEmpty();
        assertThat(searchLogs.all).singleElement()
                .satisfies(s -> assertThat(s.radiusMeters()).isEqualTo(2000.0));
    }

    // --- Spot media access URL (parking-mediated photo viewing) -----------

    @Test
    void visibleSpotReturnsSignedMediaUrlForAnyAuthenticatedUser() {
        ParkingSpot spot = createPublishedSpot(UUID.randomUUID());
        UUID viewer = UUID.randomUUID();

        SpotMediaAccess access = service.getSpotMediaAccessUrl(spot.id(), viewer);

        assertThat(access.spotId()).isEqualTo(spot.id());
        assertThat(access.mediaId()).isEqualTo(spot.mediaId());
        assertThat(access.accessUrl()).startsWith("https://signed.example/");
        assertThat(access.expiresAt()).isAfter(NOW);
        assertThat(mediaAccess.lastRequesterUserId).isEqualTo(viewer);
    }

    @Test
    void expiredSpotMediaAccessIsNotFoundForNonOwner() {
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        clock.set(NOW.plus(11, ChronoUnit.MINUTES)); // past the 10-minute window

        assertMediaAccessNotFound(spot.id(), UUID.randomUUID());
    }

    @Test
    void filledAndRejectedSpotMediaAccessIsNotFoundForNonOwner() {
        UUID owner = UUID.randomUUID();
        Instant future = NOW.plus(5, ChronoUnit.MINUTES);
        ParkingSpot filled = buildSpot(owner, ParkingSpotStatus.FILLED, future, LegalStatus.LEGAL);
        ParkingSpot rejected = buildSpot(owner, ParkingSpotStatus.REJECTED, future, LegalStatus.LEGAL);
        List.of(filled, rejected).forEach(spots::save);

        assertMediaAccessNotFound(filled.id(), UUID.randomUUID());
        assertMediaAccessNotFound(rejected.id(), UUID.randomUUID());
    }

    @Test
    void illegalOrRiskySpotMediaAccessIsNotFoundForNonOwner() {
        ParkingSpot illegal = buildSpot(UUID.randomUUID(), ParkingSpotStatus.ACTIVE,
                NOW.plus(5, ChronoUnit.MINUTES), LegalStatus.ILLEGAL_OR_RISKY);
        spots.save(illegal);

        assertMediaAccessNotFound(illegal.id(), UUID.randomUUID());
    }

    @Test
    void suspiciousSpotMediaAccessIsNotFoundForNonOwner() {
        // SUSPICIOUS spots are hidden from search, so their photo stays hidden too.
        ParkingSpot suspicious = buildSpot(UUID.randomUUID(), ParkingSpotStatus.SUSPICIOUS,
                NOW.plus(5, ChronoUnit.MINUTES), LegalStatus.LEGAL);
        spots.save(suspicious);

        assertMediaAccessNotFound(suspicious.id(), UUID.randomUUID());
    }

    @Test
    void ownerCanAccessOwnSpotMediaEvenWhenSpotIsNotVisible() {
        UUID owner = UUID.randomUUID();
        ParkingSpot expired = buildSpot(owner, ParkingSpotStatus.EXPIRED,
                NOW.minus(1, ChronoUnit.MINUTES), LegalStatus.LEGAL);
        spots.save(expired);

        SpotMediaAccess access = service.getSpotMediaAccessUrl(expired.id(), owner);

        assertThat(access.mediaId()).isEqualTo(expired.mediaId());
        assertThat(access.accessUrl()).isNotBlank();
    }

    @Test
    void unknownSpotMediaAccessIsNotFound() {
        assertMediaAccessNotFound(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void hiddenSpotMediaAccessNeverCallsMediaService() {
        ParkingSpot rejected = buildSpot(UUID.randomUUID(), ParkingSpotStatus.REJECTED,
                NOW.plus(5, ChronoUnit.MINUTES), LegalStatus.LEGAL);
        spots.save(rejected);

        assertMediaAccessNotFound(rejected.id(), UUID.randomUUID());
        assertThat(mediaAccess.requestCount).isZero();
    }

    private void assertMediaAccessNotFound(UUID spotId, UUID requesterUserId) {
        assertThatThrownBy(() -> service.getSpotMediaAccessUrl(spotId, requesterUserId))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.SPOT_NOT_FOUND);
    }

    private void assertSpotDetailNotFound(UUID spotId, UUID viewerUserId) {
        assertThatThrownBy(() -> service.getSpotForViewer(spotId, viewerUserId, false))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.SPOT_NOT_FOUND);
    }

    private ParkingSpot buildSpot(UUID owner, ParkingSpotStatus status, Instant expiresAt, LegalStatus legalStatus) {
        return new ParkingSpot(UUID.randomUUID(), owner, UUID.randomUUID(), 41.0, 29.0, null, null, false,
                Set.of(VehicleType.SEDAN), ParkingContext.STREET_PARKING, legalStatus, Set.of(),
                status, 1.0, 0, 0, expiresAt, NOW, NOW, 0L,
                status.isPendingModeration() ? null : NOW, NOW.plus(Duration.ofHours(24)), 0, null, null);
    }

    // --- Fakes -----------------------------------------------------------

    /** Captures the moderation observability signals so tests can assert on them. */
    private static final class RecordingModerationMetrics implements ModerationMetricsPort {
        private final List<ParkingSpotStatus> queueLatencyOutcomes = new ArrayList<>();
        private final List<Integer> retries = new ArrayList<>();
        private final List<ParkingSpotStatus> timeouts = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private int expiredBeforeApproved;

        @Override
        public void recordQueueLatency(Duration latency, ParkingSpotStatus outcome) {
            queueLatencyOutcomes.add(outcome);
        }

        @Override
        public void recordProcessingDuration(Duration duration, String outcome) {
            // Timing only; nothing to assert.
        }

        @Override
        public void recordRetry(int attempt) {
            retries.add(attempt);
        }

        @Override
        public void recordTimeout(ParkingSpotStatus pendingStatus) {
            timeouts.add(pendingStatus);
        }

        @Override
        public void recordModerationFailure(String reason) {
            failures.add(reason);
        }

        @Override
        public void recordExpiredBeforeApproved() {
            expiredBeforeApproved++;
        }
    }

    private static final class FakeParkingSpotRepository implements ParkingSpotRepository {
        private final Map<UUID, ParkingSpot> byId = new HashMap<>();

        @Override
        public ParkingSpot save(ParkingSpot spot) {
            byId.put(spot.id(), spot);
            return spot;
        }

        @Override
        public Optional<ParkingSpot> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<ParkingSpot> findByOwnerUserId(UUID ownerUserId) {
            return byId.values().stream().filter(s -> s.isOwnedBy(ownerUserId)).toList();
        }

        @Override
        public List<ParkingSpot> findExpiredCandidates(Instant now, int batchSize) {
            return byId.values().stream()
                    .filter(spot -> Set.of(
                            ParkingSpotStatus.ACTIVE,
                            ParkingSpotStatus.VERIFIED,
                            ParkingSpotStatus.SUSPICIOUS).contains(spot.status()))
                    .filter(spot -> spot.expiresAt() != null && spot.expiresAt().isBefore(now))
                    .limit(batchSize)
                    .toList();
        }

        @Override
        public List<ParkingSpot> findModerationTimeoutCandidates(Instant now, int batchSize) {
            return byId.values().stream()
                    .filter(ParkingSpot::isPendingModeration)
                    .filter(spot -> spot.moderationDeadlineAt().isBefore(now))
                    .limit(batchSize)
                    .toList();
        }

        @Override
        public List<ParkingSpot> findNearby(double latitude, double longitude, double radiusMeters, int limit) {
            // Geo is exercised in production (PostGIS); the fake returns all candidates
            // so the application-layer visibility filter can be asserted.
            return new ArrayList<>(byId.values());
        }
    }

    private static final class FakeVerificationRepository implements ParkingSpotVerificationRepository {
        private final List<ParkingSpotVerification> all = new ArrayList<>();

        @Override
        public ParkingSpotVerification save(ParkingSpotVerification verification) {
            all.add(verification);
            return verification;
        }

        @Override
        public boolean existsBySpotIdAndVerifierUserId(UUID spotId, UUID verifierUserId) {
            return all.stream().anyMatch(v -> v.spotId().equals(spotId) && v.verifierUserId().equals(verifierUserId));
        }
    }

    private static final class FakeStatusHistoryRepository implements ParkingSpotStatusHistoryRepository {
        private final List<ParkingSpotStatusHistory> all = new ArrayList<>();

        @Override
        public ParkingSpotStatusHistory save(ParkingSpotStatusHistory history) {
            all.add(history);
            return history;
        }
    }

    private static final class FakeViewLogRepository implements ParkingSpotViewLogRepository {
        private final List<ParkingSpotViewLog> all = new ArrayList<>();

        @Override
        public ParkingSpotViewLog save(ParkingSpotViewLog viewLog) {
            all.add(viewLog);
            return viewLog;
        }
    }

    private static final class FakeSearchLogRepository implements ParkingSpotSearchLogRepository {
        private final List<ParkingSpotSearchLog> all = new ArrayList<>();

        @Override
        public ParkingSpotSearchLog save(ParkingSpotSearchLog searchLog) {
            all.add(searchLog);
            return searchLog;
        }
    }

    private static final class FakeMediaAccessPort implements MediaAccessPort {
        private UUID lastRequesterUserId;
        private int requestCount;

        @Override
        public MediaAccessGrant requestAccessUrl(UUID mediaId, UUID requesterUserId) {
            requestCount++;
            lastRequesterUserId = requesterUserId;
            return new MediaAccessGrant(mediaId, "https://signed.example/" + mediaId,
                    NOW.plus(5, ChronoUnit.MINUTES));
        }
    }

    private static final class FakeMediaReadinessPort implements MediaReadinessPort {
        private int checkCount;
        private UUID lastMediaId;
        private UUID lastOwnerUserId;
        private ParkingException toThrow;

        @Override
        public void ensureMediaReady(UUID mediaId, UUID ownerUserId) {
            checkCount++;
            lastMediaId = mediaId;
            lastOwnerUserId = ownerUserId;
            if (toThrow != null) {
                throw toThrow;
            }
        }
    }

    private static final class FakeOutboxEventAppender implements OutboxEventAppender {
        private final List<ParkingEvent> events = new ArrayList<>();

        @Override
        public void append(ParkingEvent event) {
            events.add(event);
        }
    }

    /** Test clock whose instant can be advanced. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
