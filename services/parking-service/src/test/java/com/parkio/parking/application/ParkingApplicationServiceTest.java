package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.application.command.CreateSpotCommand;
import com.parkio.parking.application.command.SearchNearbyQuery;
import com.parkio.parking.application.port.OutboxEventAppender;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.application.port.ParkingSpotSearchLogRepository;
import com.parkio.parking.application.port.ParkingSpotStatusHistoryRepository;
import com.parkio.parking.application.port.ParkingSpotVerificationRepository;
import com.parkio.parking.application.port.ParkingSpotViewLogRepository;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotSearchLog;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.domain.ParkingSpotVerification;
import com.parkio.parking.domain.ParkingSpotViewLog;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.domain.VerificationResult;
import com.parkio.parking.domain.event.ParkingEvent;
import com.parkio.parking.domain.event.ParkingSpotClaimedEvent;
import com.parkio.parking.domain.event.ParkingSpotCreatedEvent;
import com.parkio.parking.domain.event.ParkingSpotExpiredEvent;
import com.parkio.parking.domain.event.ParkingSpotMarkedFilledEvent;
import com.parkio.parking.domain.event.ParkingSpotVerifiedEvent;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.time.Clock;
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

    private FakeParkingSpotRepository spots;
    private FakeVerificationRepository verifications;
    private FakeStatusHistoryRepository statusHistory;
    private FakeViewLogRepository viewLogs;
    private FakeSearchLogRepository searchLogs;
    private FakeOutboxEventAppender outbox;
    private MutableClock clock;
    private ParkingApplicationService service;

    @BeforeEach
    void setUp() {
        spots = new FakeParkingSpotRepository();
        verifications = new FakeVerificationRepository();
        statusHistory = new FakeStatusHistoryRepository();
        viewLogs = new FakeViewLogRepository();
        searchLogs = new FakeSearchLogRepository();
        outbox = new FakeOutboxEventAppender();
        clock = new MutableClock(NOW);
        service = new ParkingApplicationService(spots, verifications, statusHistory, viewLogs, searchLogs,
                outbox, new ParkingSearchSettings(1000, 10, 50000, 50), clock);
    }

    private CreateSpotCommand createCommand(UUID owner, LegalStatus legalStatus) {
        return new CreateSpotCommand(owner, UUID.randomUUID(), 41.0082, 28.9784, "Main St", "Nice spot",
                false, Set.of(VehicleType.SEDAN), ParkingContext.STREET_PARKING, legalStatus, Set.of());
    }

    @Test
    void createsLegalSpotAsActiveAndEmitsEvent() {
        UUID owner = UUID.randomUUID();

        ParkingSpot spot = service.createSpot(createCommand(owner, LegalStatus.LEGAL));

        assertThat(spot.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(spot.expiresAt()).isEqualTo(NOW.plus(10, ChronoUnit.MINUTES));
        assertThat(spots.byId).containsKey(spot.id());
        assertThat(statusHistory.all).singleElement()
                .satisfies(h -> assertThat(h.newStatus()).isEqualTo(ParkingSpotStatus.ACTIVE));
        assertThat(outbox.events).singleElement().isInstanceOf(ParkingSpotCreatedEvent.class);
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
    void ownerCannotVerifyOwnSpot() {
        UUID owner = UUID.randomUUID();
        ParkingSpot spot = service.createSpot(createCommand(owner, LegalStatus.LEGAL));

        assertThatThrownBy(() -> service.verifySpot(spot.id(), owner, VerificationResult.AVAILABLE))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.OWNER_CANNOT_VERIFY);
    }

    @Test
    void duplicateVerificationBySameUserIsRejected() {
        UUID owner = UUID.randomUUID();
        UUID verifier = UUID.randomUUID();
        ParkingSpot spot = service.createSpot(createCommand(owner, LegalStatus.LEGAL));

        service.verifySpot(spot.id(), verifier, VerificationResult.AVAILABLE);

        assertThatThrownBy(() -> service.verifySpot(spot.id(), verifier, VerificationResult.AVAILABLE))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.ALREADY_VERIFIED);
    }

    @Test
    void availableVerificationsVerifyAndExtendExpiration() {
        UUID owner = UUID.randomUUID();
        ParkingSpot spot = service.createSpot(createCommand(owner, LegalStatus.LEGAL));

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
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
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
        ParkingSpot spot = service.createSpot(createCommand(UUID.randomUUID(), LegalStatus.LEGAL));
        service.verifySpot(spot.id(), UUID.randomUUID(), VerificationResult.ILLEGAL_OR_RISKY);
        outbox.events.clear();
        statusHistory.all.clear();

        service.rejectSpotByModerator(spot.id());

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
        ParkingSpot spot = service.createSpot(createCommand(owner, LegalStatus.LEGAL));

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
        ParkingSpot spot = service.createSpot(createCommand(owner, LegalStatus.LEGAL));

        ParkingSpot claimed = service.claimSpot(spot.id(), claimer);

        assertThat(claimed.status()).isEqualTo(ParkingSpotStatus.FILLED);
        assertThat(outbox.events).filteredOn(e -> e instanceof ParkingSpotClaimedEvent).hasSize(1);
    }

    @Test
    void ownerCannotClaimOwnSpot() {
        UUID owner = UUID.randomUUID();
        ParkingSpot spot = service.createSpot(createCommand(owner, LegalStatus.LEGAL));

        assertThatThrownBy(() -> service.claimSpot(spot.id(), owner))
                .isInstanceOf(ParkingException.class)
                .extracting(e -> ((ParkingException) e).errorCode())
                .isEqualTo(ParkingErrorCode.OWNER_CANNOT_CLAIM);
    }

    @Test
    void expiredSpotCannotBeVerified() {
        UUID owner = UUID.randomUUID();
        ParkingSpot spot = service.createSpot(createCommand(owner, LegalStatus.LEGAL));

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

    private ParkingSpot buildSpot(UUID owner, ParkingSpotStatus status, Instant expiresAt, LegalStatus legalStatus) {
        return new ParkingSpot(UUID.randomUUID(), owner, UUID.randomUUID(), 41.0, 29.0, null, null, false,
                Set.of(VehicleType.SEDAN), ParkingContext.STREET_PARKING, legalStatus, Set.of(),
                status, 1.0, 0, 0, expiresAt, NOW, NOW, 0L);
    }

    // --- Fakes -----------------------------------------------------------

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
                    .filter(spot -> spot.expiresAt().isBefore(now))
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
