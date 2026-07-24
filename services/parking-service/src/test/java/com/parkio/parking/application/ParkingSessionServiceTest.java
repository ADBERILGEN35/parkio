package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.application.port.OutboxEventAppender;
import com.parkio.parking.application.port.ParkingSessionRepository;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionStatus;
import com.parkio.parking.domain.ParkingSource;
import com.parkio.parking.domain.event.ParkingEvent;
import com.parkio.parking.domain.event.ParkingSessionCancelledEvent;
import com.parkio.parking.domain.event.ParkingSessionCompletedEvent;
import com.parkio.parking.domain.event.ParkingSessionStartedEvent;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParkingSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T09:00:00Z");
    private static final Comparator<ParkingSession> HISTORY_ORDER =
            Comparator.comparing(ParkingSession::getStartedAt).reversed()
                    .thenComparing(
                            session -> session.getId().toString(), Comparator.reverseOrder());

    private FakeParkingSessionRepository repository;
    private FakeOutboxEventAppender outbox;
    private ParkingSessionService service;

    @BeforeEach
    void setUp() {
        repository = new FakeParkingSessionRepository();
        outbox = new FakeOutboxEventAppender();
        service = new ParkingSessionService(repository, outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void startsSessionWhenUserHasNoActiveSession() {
        UUID userId = UUID.randomUUID();

        ParkingSession session = service.startSession(
                userId, ParkingSource.MANUAL, 41.0082, 28.9784,
                new BigDecimal("25.00"), null);

        assertThat(session.getUserId()).isEqualTo(userId);
        assertThat(session.getStatus()).isEqualTo(ParkingSessionStatus.ACTIVE);
        assertThat(session.getStartedAt()).isEqualTo(NOW);
        assertThat(repository.byId).containsKey(session.getId());
        assertThat(outbox.events).singleElement()
                .isInstanceOf(ParkingSessionStartedEvent.class)
                .satisfies(event -> {
                    ParkingSessionStartedEvent started = (ParkingSessionStartedEvent) event;
                    assertThat(started.sessionId()).isEqualTo(session.getId());
                    assertThat(started.userId()).isEqualTo(userId);
                    assertThat(started.status()).isEqualTo(ParkingSessionStatus.ACTIVE);
                    assertThat(started.source()).isEqualTo(ParkingSource.MANUAL);
                    assertThat(started.startedAt()).isEqualTo(NOW);
                    assertThat(started.aggregateType()).isEqualTo(ParkingEvent.SESSION_AGGREGATE_TYPE);
                    assertThat(started.eventType()).isEqualTo(ParkingSessionStartedEvent.TYPE);
                });
    }

    @Test
    void rejectsSecondActiveSessionForSameUserWithoutEvent() {
        UUID userId = UUID.randomUUID();
        service.startSession(userId, ParkingSource.AUTO, 41.0, 29.0, null, null);
        outbox.events.clear();

        assertThatThrownBy(() -> service.startSession(
                userId, ParkingSource.MANUAL, 41.1, 29.1, null, null))
                .isInstanceOf(ParkingException.class)
                .extracting(exception -> ((ParkingException) exception).errorCode())
                .isEqualTo(ParkingErrorCode.ACTIVE_PARKING_SESSION_EXISTS);
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void completesAndCancelsEmitTerminalEventsWithoutCoordinates() {
        UUID firstUser = UUID.randomUUID();
        UUID secondUser = UUID.randomUUID();
        ParkingSession completed = service.startSession(
                firstUser, ParkingSource.FACILITY, 41.0, 29.0, null, null);
        ParkingSession cancelled = service.startSession(
                secondUser, ParkingSource.CURB, 40.9, 29.1, null, null);
        outbox.events.clear();

        assertThat(service.completeSession(firstUser, completed.getId()).getStatus())
                .isEqualTo(ParkingSessionStatus.COMPLETED);
        assertThat(service.cancelSession(secondUser, cancelled.getId()).getStatus())
                .isEqualTo(ParkingSessionStatus.CANCELLED);

        assertThat(outbox.events).hasSize(2);
        assertThat(outbox.events.get(0)).isInstanceOf(ParkingSessionCompletedEvent.class)
                .satisfies(event -> {
                    ParkingSessionCompletedEvent completedEvent = (ParkingSessionCompletedEvent) event;
                    assertThat(completedEvent.sessionId()).isEqualTo(completed.getId());
                    assertThat(completedEvent.status()).isEqualTo(ParkingSessionStatus.COMPLETED);
                    assertThat(completedEvent.source()).isEqualTo(ParkingSource.FACILITY);
                    assertThat(completedEvent.startedAt()).isEqualTo(NOW);
                    assertThat(completedEvent.endedAt()).isEqualTo(NOW);
                });
        assertThat(outbox.events.get(1)).isInstanceOf(ParkingSessionCancelledEvent.class)
                .satisfies(event -> {
                    ParkingSessionCancelledEvent cancelledEvent = (ParkingSessionCancelledEvent) event;
                    assertThat(cancelledEvent.sessionId()).isEqualTo(cancelled.getId());
                    assertThat(cancelledEvent.status()).isEqualTo(ParkingSessionStatus.CANCELLED);
                    assertThat(cancelledEvent.source()).isEqualTo(ParkingSource.CURB);
                    assertThat(cancelledEvent.endedAt()).isEqualTo(NOW);
                });
    }

    @Test
    void completeAfterCancelEmitsNoSecondTerminalEvent() {
        UUID userId = UUID.randomUUID();
        ParkingSession session = service.startSession(
                userId, ParkingSource.MANUAL, 41.0, 29.0, null, null);
        service.cancelSession(userId, session.getId());
        outbox.events.clear();

        assertThatThrownBy(() -> service.completeSession(userId, session.getId()))
                .isInstanceOf(ParkingException.class)
                .extracting(exception -> ((ParkingException) exception).errorCode())
                .isEqualTo(ParkingErrorCode.PARKING_SESSION_NOT_ACTIVE);
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void communityStartEmitsStartedWithCommunitySource() {
        UUID userId = UUID.randomUUID();

        ParkingSession session = service.startSession(
                userId, ParkingSource.COMMUNITY, 41.0, 29.0, null, null);

        assertThat(outbox.events).singleElement()
                .isInstanceOf(ParkingSessionStartedEvent.class)
                .extracting(event -> ((ParkingSessionStartedEvent) event).source())
                .isEqualTo(ParkingSource.COMMUNITY);
        assertThat(session.getParkingSource()).isEqualTo(ParkingSource.COMMUNITY);
    }

    @Test
    void notFoundTerminalTransitionEmitsNoEvent() {
        UUID userId = UUID.randomUUID();
        outbox.events.clear();

        assertThatThrownBy(() -> service.completeSession(userId, UUID.randomUUID()))
                .isInstanceOf(ParkingException.class)
                .extracting(exception -> ((ParkingException) exception).errorCode())
                .isEqualTo(ParkingErrorCode.PARKING_SESSION_NOT_FOUND);
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void deletesDoNotEmitLifecycleEvents() {
        UUID owner = UUID.randomUUID();
        ParkingSession completed = service.startSession(
                owner, ParkingSource.MANUAL, 41.0, 29.0, null, null);
        service.completeSession(owner, completed.getId());
        outbox.events.clear();

        service.deleteTerminalSession(owner, completed.getId());
        service.deleteTerminalHistory(owner);

        assertThat(outbox.events).isEmpty();
    }

    @Test
    void completesAndCancelsOnlyOwnedSessions() {
        UUID firstUser = UUID.randomUUID();
        UUID secondUser = UUID.randomUUID();
        ParkingSession completed = service.startSession(
                firstUser, ParkingSource.FACILITY, 41.0, 29.0, null, null);
        ParkingSession cancelled = service.startSession(
                secondUser, ParkingSource.CURB, 40.9, 29.1, null, null);
        int savesBeforeTransitions = repository.saveCalls;
        outbox.events.clear();

        assertThat(service.completeSession(firstUser, completed.getId()).getStatus())
                .isEqualTo(ParkingSessionStatus.COMPLETED);
        assertThat(service.cancelSession(secondUser, cancelled.getId()).getStatus())
                .isEqualTo(ParkingSessionStatus.CANCELLED);
        assertThat(repository.saveCalls).isEqualTo(savesBeforeTransitions + 2);
        assertThat(outbox.events).hasSize(2);

        assertThatThrownBy(() -> service.completeSession(secondUser, completed.getId()))
                .isInstanceOf(ParkingException.class)
                .extracting(exception -> ((ParkingException) exception).errorCode())
                .isEqualTo(ParkingErrorCode.PARKING_SESSION_NOT_FOUND);
        assertThat(outbox.events).hasSize(2);
    }

    @Test
    void activeAndHistoryQueriesRemainSeparated() {
        UUID userId = UUID.randomUUID();
        ParkingSession completed = service.startSession(
                userId, ParkingSource.COMMUNITY, 41.0, 29.0, null, null);
        service.completeSession(userId, completed.getId());
        ParkingSession active = service.startSession(
                userId, ParkingSource.AUTO, 41.1, 29.1, null, null);

        assertThat(service.findActive(userId)).contains(active);
        assertThat(service.findHistory(userId, 10).sessions())
                .containsExactly(completed)
                .allMatch(session -> !session.isActive());
    }

    @Test
    void returnsBoundedHistoryWithStableTimestampAndIdCursorOrdering() {
        UUID userId = UUID.randomUUID();
        ParkingSession sameTimeFirst = terminalSession(userId, NOW.minusSeconds(10));
        ParkingSession sameTimeSecond = terminalSession(userId, NOW.minusSeconds(10));
        ParkingSession older = terminalSession(userId, NOW.minusSeconds(20));
        ParkingSession oldest = terminalSession(userId, NOW.minusSeconds(30));
        ParkingSession active = ParkingSession.start(
                userId, ParkingSource.AUTO, 41.2, 29.2, null, null, NOW);
        repository.save(active);

        List<ParkingSession> expected = new ArrayList<>(
                List.of(sameTimeFirst, sameTimeSecond, older, oldest));
        expected.sort(HISTORY_ORDER);

        ParkingSessionHistoryPage firstPage = service.findHistory(userId, 2);

        assertThat(firstPage.sessions()).containsExactlyElementsOf(expected.subList(0, 2));
        assertThat(firstPage.hasNext()).isTrue();

        ParkingSessionHistoryCursor cursor = firstPage.nextCursor().orElseThrow();
        ParkingSessionHistoryPage secondPage = service.findHistory(userId, cursor, 2);

        assertThat(secondPage.sessions()).containsExactlyElementsOf(expected.subList(2, 4));
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.nextCursor()).isEmpty();
        assertThat(firstPage.sessions()).doesNotContain(active);
        assertThat(secondPage.sessions()).doesNotContain(active);
    }

    @Test
    void rejectsUnboundedHistoryPageSizes() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.findHistory(userId, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("history pageSize must be between 1 and 100");
        assertThatThrownBy(() -> service.findHistory(userId, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("history pageSize must be between 1 and 100");
    }

    @Test
    void deletesOwnedTerminalSessionsAndIsIdempotentForMissingIds() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        ParkingSession completed = service.startSession(
                owner, ParkingSource.MANUAL, 41.0082, 28.9784, null, null);
        service.completeSession(owner, completed.getId());
        ParkingSession cancelled = service.startSession(
                owner, ParkingSource.AUTO, 41.1, 29.1, null, null);
        service.cancelSession(owner, cancelled.getId());
        ParkingSession foreign = service.startSession(
                stranger, ParkingSource.CURB, 40.0, 29.0, null, null);
        service.completeSession(stranger, foreign.getId());

        service.deleteTerminalSession(owner, completed.getId());
        assertThat(repository.byId).doesNotContainKey(completed.getId());

        service.deleteTerminalSession(owner, completed.getId());
        service.deleteTerminalSession(owner, UUID.randomUUID());
        service.deleteTerminalSession(owner, foreign.getId());
        assertThat(repository.byId).containsKey(foreign.getId());
        assertThat(repository.byId).containsKey(cancelled.getId());
    }

    @Test
    void rejectsDeletingOwnedActiveSessionWithoutMutation() {
        UUID owner = UUID.randomUUID();
        ParkingSession active = service.startSession(
                owner, ParkingSource.MANUAL, 41.0, 29.0, null, null);

        assertThatThrownBy(() -> service.deleteTerminalSession(owner, active.getId()))
                .isInstanceOf(ParkingException.class)
                .extracting(exception -> ((ParkingException) exception).errorCode())
                .isEqualTo(ParkingErrorCode.PARKING_SESSION_NOT_TERMINAL);
        assertThat(repository.byId).containsKey(active.getId());
        assertThat(service.findActive(owner)).contains(active);
    }

    @Test
    void deleteHistoryRemovesOnlyOwnedTerminalRowsAndPreservesActive() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        ParkingSession completed = service.startSession(
                owner, ParkingSource.MANUAL, 41.0, 29.0, null, null);
        service.completeSession(owner, completed.getId());
        ParkingSession cancelled = service.startSession(
                owner, ParkingSource.AUTO, 41.1, 29.1, null, null);
        service.cancelSession(owner, cancelled.getId());
        ParkingSession active = service.startSession(
                owner, ParkingSource.CURB, 41.2, 29.2, null, null);
        ParkingSession foreign = service.startSession(
                stranger, ParkingSource.FACILITY, 40.0, 29.0, null, null);
        service.completeSession(stranger, foreign.getId());

        service.deleteTerminalHistory(owner);
        service.deleteTerminalHistory(owner);

        assertThat(repository.byId).doesNotContainKeys(completed.getId(), cancelled.getId());
        assertThat(repository.byId).containsKeys(active.getId(), foreign.getId());
        assertThat(service.findActive(owner)).contains(active);
        assertThat(service.findHistory(owner, 10).sessions()).isEmpty();
        assertThat(service.findHistory(stranger, 10).sessions()).containsExactly(foreign);
    }

    private ParkingSession terminalSession(UUID userId, Instant startedAt) {
        ParkingSession session = ParkingSession.start(
                userId, ParkingSource.MANUAL, 41.0, 29.0, null, null, startedAt);
        session.complete(startedAt.plusSeconds(1));
        return repository.save(session);
    }

    private static final class FakeParkingSessionRepository implements ParkingSessionRepository {

        private final Map<UUID, ParkingSession> byId = new LinkedHashMap<>();
        private int saveCalls;

        @Override
        public ParkingSession save(ParkingSession session) {
            saveCalls++;
            byId.put(session.getId(), session);
            return session;
        }

        @Override
        public Optional<ParkingSession> findActiveByUserId(UUID userId) {
            return byId.values().stream()
                    .filter(session -> session.getUserId().equals(userId))
                    .filter(ParkingSession::isActive)
                    .findFirst();
        }

        @Override
        public Optional<ParkingSession> findByIdAndUserId(UUID id, UUID userId) {
            return Optional.ofNullable(byId.get(id))
                    .filter(session -> session.getUserId().equals(userId));
        }

        @Override
        public ParkingSessionHistoryPage findHistoryByUserId(UUID userId, int pageSize) {
            return page(historyFor(userId), pageSize);
        }

        @Override
        public ParkingSessionHistoryPage findHistoryByUserId(
                UUID userId, ParkingSessionHistoryCursor cursor, int pageSize) {
            List<ParkingSession> remaining = historyFor(userId).stream()
                    .filter(session -> session.getStartedAt().isBefore(cursor.startedAt())
                            || (session.getStartedAt().equals(cursor.startedAt())
                                    && session.getId().toString().compareTo(cursor.id().toString()) < 0))
                    .toList();
            return page(remaining, pageSize);
        }

        @Override
        public int deleteTerminalByIdAndUserId(UUID id, UUID userId) {
            ParkingSession existing = byId.get(id);
            if (existing == null
                    || !existing.getUserId().equals(userId)
                    || existing.isActive()) {
                return 0;
            }
            byId.remove(id);
            return 1;
        }

        @Override
        public int deleteAllTerminalByUserId(UUID userId) {
            List<UUID> removable = byId.values().stream()
                    .filter(session -> session.getUserId().equals(userId))
                    .filter(session -> !session.isActive())
                    .map(ParkingSession::getId)
                    .toList();
            removable.forEach(byId::remove);
            return removable.size();
        }

        @Override
        public Optional<ParkingSessionStatus> findStatusByIdAndUserId(UUID id, UUID userId) {
            return Optional.ofNullable(byId.get(id))
                    .filter(session -> session.getUserId().equals(userId))
                    .map(ParkingSession::getStatus);
        }

        private List<ParkingSession> historyFor(UUID userId) {
            List<ParkingSession> history = new ArrayList<>(byId.values().stream()
                    .filter(session -> session.getUserId().equals(userId))
                    .filter(session -> !session.isActive())
                    .toList());
            history.sort(HISTORY_ORDER);
            return List.copyOf(history);
        }

        private static ParkingSessionHistoryPage page(
                List<ParkingSession> history, int pageSize) {
            ParkingSessionRepository.requireValidHistoryPageSize(pageSize);
            boolean hasNext = history.size() > pageSize;
            return new ParkingSessionHistoryPage(
                    history.subList(0, Math.min(history.size(), pageSize)), hasNext);
        }
    }

    private static final class FakeOutboxEventAppender implements OutboxEventAppender {
        private final List<ParkingEvent> events = new ArrayList<>();

        @Override
        public void append(ParkingEvent event) {
            events.add(event);
        }
    }
}
