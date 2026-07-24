package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.application.port.ParkingSessionRepository;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionStatus;
import com.parkio.parking.domain.ParkingSource;
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
    private ParkingSessionService service;

    @BeforeEach
    void setUp() {
        repository = new FakeParkingSessionRepository();
        service = new ParkingSessionService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
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
    }

    @Test
    void rejectsSecondActiveSessionForSameUser() {
        UUID userId = UUID.randomUUID();
        service.startSession(userId, ParkingSource.AUTO, 41.0, 29.0, null, null);

        assertThatThrownBy(() -> service.startSession(
                userId, ParkingSource.MANUAL, 41.1, 29.1, null, null))
                .isInstanceOf(ParkingException.class)
                .extracting(exception -> ((ParkingException) exception).errorCode())
                .isEqualTo(ParkingErrorCode.ACTIVE_PARKING_SESSION_EXISTS);
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

        assertThat(service.completeSession(firstUser, completed.getId()).getStatus())
                .isEqualTo(ParkingSessionStatus.COMPLETED);
        assertThat(service.cancelSession(secondUser, cancelled.getId()).getStatus())
                .isEqualTo(ParkingSessionStatus.CANCELLED);
        assertThat(repository.saveCalls).isEqualTo(savesBeforeTransitions + 2);

        assertThatThrownBy(() -> service.completeSession(secondUser, completed.getId()))
                .isInstanceOf(ParkingException.class)
                .extracting(exception -> ((ParkingException) exception).errorCode())
                .isEqualTo(ParkingErrorCode.PARKING_SESSION_NOT_FOUND);
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
}
