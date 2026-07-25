package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.OutboxEventAppender;
import com.parkio.parking.application.port.ParkingSessionRepository;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionCompletionReason;
import com.parkio.parking.domain.ParkingSessionCompletionType;
import com.parkio.parking.domain.ParkingSessionReminderStage;
import com.parkio.parking.domain.ParkingSessionStatus;
import com.parkio.parking.domain.ParkingSessionStalePolicy;
import com.parkio.parking.domain.ParkingSource;
import com.parkio.parking.domain.event.ParkingEvent;
import com.parkio.parking.domain.event.ParkingSessionCompletedEvent;
import com.parkio.parking.domain.event.ParkingSessionReminderRequestedEvent;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.config.ParkingProperties;
import com.parkio.parking.infrastructure.metrics.ParkingSessionLifecycleMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * RC hardening coverage for parking-session stale lifecycle: optimistic-lock races,
 * independent feature flags, reminder notification identity, and retention defaults.
 *
 * <p>Gaps not covered here (too heavy for CI without multi-node Kafka + dual app
 * instances): multi-node scheduler fencing across Kafka partitions, end-to-end
 * notification-service inbox dedupe of ReminderRequested redelivery, and
 * cross-replica retention purge races. Those remain ops/manual or future IT.
 */
class ParkingSessionStaleLifecycleHardeningTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");

    private FakeParkingSessionRepository repository;
    private FakeOutboxEventAppender outbox;
    private ParkingProperties properties;
    private ParkingSessionService service;
    private ParkingSessionStaleRowProcessor processor;

    @BeforeEach
    void setUp() {
        repository = new FakeParkingSessionRepository();
        outbox = new FakeOutboxEventAppender();
        properties = new ParkingProperties();
        ParkingSessionStalePolicy policy = ParkingSessionStalePolicy.defaults();
        processor = new ParkingSessionStaleRowProcessor(repository, outbox, policy);
        service = new ParkingSessionService(
                repository,
                outbox,
                Clock.fixed(NOW, ZoneOffset.UTC),
                policy,
                properties,
                new ParkingSessionLifecycleMetrics(new SimpleMeterRegistry()),
                processor);
    }

    @Test
    void tryAutoCompleteReturnsFalseOnOptimisticLockConflict() {
        Instant started = NOW.minus(ParkingSessionStalePolicy.defaults().autoCompleteAfter())
                .minusSeconds(60);
        ParkingSession session = ParkingSession.start(
                UUID.randomUUID(), ParkingSource.MANUAL, 41.0, 29.0, null, null, started);
        ParkingSessionRepository sessions = mock(ParkingSessionRepository.class);
        OutboxEventAppender events = mock(OutboxEventAppender.class);
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(sessions.save(any(ParkingSession.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(ParkingSession.class, session.getId()));

        ParkingSessionStaleRowProcessor racing = new ParkingSessionStaleRowProcessor(
                sessions, events, ParkingSessionStalePolicy.defaults());

        assertThat(racing.tryAutoComplete(session.getId(), NOW)).isFalse();
        verify(events, never()).append(any());
    }

    @Test
    void tryAutoCompleteReturnsFalseWhenManualCompleteAlreadyWon() {
        UUID userId = UUID.randomUUID();
        Instant started = NOW.minus(ParkingSessionStalePolicy.defaults().autoCompleteAfter())
                .minusSeconds(60);
        ParkingSession session = ParkingSession.start(
                userId, ParkingSource.MANUAL, 41.0, 29.0, null, null, started);
        repository.save(session);
        service.completeSession(userId, session.getId());
        outbox.events.clear();

        assertThat(processor.tryAutoComplete(session.getId(), NOW)).isFalse();
        assertThat(outbox.events).isEmpty();
        ParkingSession saved = repository.byId.get(session.getId());
        assertThat(saved.getStatus()).isEqualTo(ParkingSessionStatus.COMPLETED);
        assertThat(saved.getCompletionType()).isEqualTo(ParkingSessionCompletionType.MANUAL);
        assertThat(saved.getCompletionReason()).isEqualTo(ParkingSessionCompletionReason.MANUAL);
    }

    @Test
    void confirmActiveThenSchedulerSkipsAutoComplete() {
        UUID userId = UUID.randomUUID();
        Instant started = NOW.minus(ParkingSessionStalePolicy.defaults().autoCompleteAfter())
                .minusSeconds(60);
        ParkingSession session = ParkingSession.start(
                userId, ParkingSource.MANUAL, 41.0, 29.0, null, null, started);
        repository.save(session);

        service.confirmActiveSession(userId, session.getId());
        outbox.events.clear();

        assertThat(service.autoCompleteStaleSessionsPage(10).succeeded()).isZero();
        assertThat(repository.byId.get(session.getId()).isActive()).isTrue();
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void tryAutoCompleteReturnsFalseWhenConfirmActiveWinsOptimisticLock() {
        Instant started = NOW.minus(ParkingSessionStalePolicy.defaults().autoCompleteAfter())
                .minusSeconds(60);
        ParkingSession session = ParkingSession.start(
                UUID.randomUUID(), ParkingSource.MANUAL, 41.0, 29.0, null, null, started);
        ParkingSessionRepository sessions = mock(ParkingSessionRepository.class);
        OutboxEventAppender events = mock(OutboxEventAppender.class);
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(sessions.save(any(ParkingSession.class)))
                .thenThrow(new OptimisticLockingFailureException("confirm-active won"));

        ParkingSessionStaleRowProcessor racing = new ParkingSessionStaleRowProcessor(
                sessions, events, ParkingSessionStalePolicy.defaults());

        assertThat(racing.tryAutoComplete(session.getId(), NOW)).isFalse();
        verify(events, never()).append(any());
    }

    @Test
    void trySendReminderReturnsFalseOnOptimisticLockAndKeepsStageIdentityStable() {
        Instant started = NOW.minus(ParkingSessionStalePolicy.defaults().confirmAfter())
                .minusSeconds(60);
        ParkingSession session = ParkingSession.start(
                UUID.randomUUID(), ParkingSource.MANUAL, 41.0, 29.0, null, null, started);
        ParkingSessionRepository sessions = mock(ParkingSessionRepository.class);
        OutboxEventAppender events = mock(OutboxEventAppender.class);
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(sessions.save(any(ParkingSession.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(ParkingSession.class, session.getId()));

        ParkingSessionStaleRowProcessor racing = new ParkingSessionStaleRowProcessor(
                sessions, events, ParkingSessionStalePolicy.defaults());

        assertThat(racing.trySendReminder(
                session.getId(), ParkingSessionReminderStage.FIRST, NOW)).isFalse();
        verify(events, never()).append(any());
        // Domain mutates before save; OLFE means the stage was not persisted.
        verify(sessions).save(any(ParkingSession.class));
    }

    @Test
    void reminderEventCarriesStableSessionAndStageIdentityForNotificationDedupe() {
        Instant started = NOW.minus(ParkingSessionStalePolicy.defaults().confirmAfter())
                .minusSeconds(60);
        ParkingSession session = ParkingSession.start(
                UUID.randomUUID(), ParkingSource.MANUAL, 41.0, 29.0, null, null, started);
        repository.save(session);
        outbox.events.clear();

        assertThat(processor.trySendReminder(
                session.getId(), ParkingSessionReminderStage.FIRST, NOW)).isTrue();
        assertThat(processor.trySendReminder(
                session.getId(), ParkingSessionReminderStage.FIRST, NOW)).isFalse();

        assertThat(outbox.events).singleElement()
                .isInstanceOf(ParkingSessionReminderRequestedEvent.class)
                .satisfies(event -> {
                    ParkingSessionReminderRequestedEvent reminder =
                            (ParkingSessionReminderRequestedEvent) event;
                    assertThat(reminder.sessionId()).isEqualTo(session.getId());
                    assertThat(reminder.userId()).isEqualTo(session.getUserId());
                    assertThat(reminder.stage()).isEqualTo(ParkingSessionReminderStage.FIRST);
                    assertThat(reminder.eventId()).isNotNull();
                    assertThat(reminder.aggregateId()).isEqualTo(session.getId());
                });
        assertThat(repository.byId.get(session.getId()).getReminderStage())
                .isEqualTo(ParkingSessionReminderStage.FIRST);
    }

    @Test
    void remindersFlagDisablesRemindersWithoutBlockingAutoComplete() {
        properties.getSession().setRemindersEnabled(false);
        UUID reminderUser = UUID.randomUUID();
        UUID staleUser = UUID.randomUUID();
        Instant reminderStarted = NOW.minus(ParkingSessionStalePolicy.defaults().confirmAfter())
                .minusSeconds(60);
        Instant staleStarted = NOW.minus(ParkingSessionStalePolicy.defaults().autoCompleteAfter())
                .minusSeconds(60);
        repository.save(ParkingSession.start(
                reminderUser, ParkingSource.MANUAL, 41.0, 29.0, null, null, reminderStarted));
        repository.save(ParkingSession.start(
                staleUser, ParkingSource.MANUAL, 41.1, 29.1, null, null, staleStarted));
        outbox.events.clear();

        assertThat(service.sendDueRemindersPage(ParkingSessionReminderStage.FIRST, 10).exhausted())
                .isTrue();
        assertThat(service.autoCompleteStaleSessionsPage(10).succeeded()).isEqualTo(1);
        assertThat(outbox.events).singleElement().isInstanceOf(ParkingSessionCompletedEvent.class);
    }

    @Test
    void autoCompleteFlagDisablesCompletionWithoutBlockingReminders() {
        properties.getSession().setAutoCompleteEnabled(false);
        UUID reminderUser = UUID.randomUUID();
        UUID staleUser = UUID.randomUUID();
        Instant reminderStarted = NOW.minus(ParkingSessionStalePolicy.defaults().confirmAfter())
                .minusSeconds(60);
        Instant staleStarted = NOW.minus(ParkingSessionStalePolicy.defaults().autoCompleteAfter())
                .minusSeconds(60);
        repository.save(ParkingSession.start(
                reminderUser, ParkingSource.MANUAL, 41.0, 29.0, null, null, reminderStarted));
        ParkingSession stale = ParkingSession.start(
                staleUser, ParkingSource.MANUAL, 41.1, 29.1, null, null, staleStarted);
        // Already past both reminder stages so this row only probes auto-complete.
        stale.markReminderSent(ParkingSessionReminderStage.FIRST, reminderStarted);
        stale.markReminderSent(ParkingSessionReminderStage.SECOND, reminderStarted);
        repository.save(stale);
        outbox.events.clear();

        assertThat(service.sendDueRemindersPage(ParkingSessionReminderStage.FIRST, 10).succeeded())
                .isEqualTo(1);
        assertThat(service.autoCompleteStaleSessionsPage(10).exhausted()).isTrue();
        assertThat(outbox.events).singleElement()
                .isInstanceOf(ParkingSessionReminderRequestedEvent.class);
        assertThat(repository.byId.get(stale.getId()).isActive()).isTrue();
    }

    @Test
    void notificationFlagDisablesRemindersIndependently() {
        properties.getSession().setNotificationEnabled(false);
        Instant started = NOW.minus(ParkingSessionStalePolicy.defaults().confirmAfter())
                .minusSeconds(60);
        repository.save(ParkingSession.start(
                UUID.randomUUID(), ParkingSource.MANUAL, 41.0, 29.0, null, null, started));
        outbox.events.clear();

        assertThat(service.sendDueRemindersPage(ParkingSessionReminderStage.FIRST, 10).exhausted())
                .isTrue();
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void retentionDisabledByDefaultAndPurgesWhenEnabled() {
        UUID userId = UUID.randomUUID();
        Instant started = NOW.minusSeconds(86_400 * 10);
        ParkingSession completed = ParkingSession.start(
                userId, ParkingSource.MANUAL, 41.0, 29.0, null, null, started);
        completed.complete(started.plusSeconds(1), ParkingSessionCompletionReason.MANUAL);
        repository.save(completed);

        assertThat(properties.getSession().isRetentionEnabled()).isFalse();
        assertThat(service.purgeExpiredHistoryPage(10).exhausted()).isTrue();
        assertThat(repository.byId).containsKey(completed.getId());

        properties.getSession().setRetentionEnabled(true);
        properties.getSession().setRetentionAfter(java.time.Duration.ofDays(1));
        assertThat(service.purgeExpiredHistoryPage(10).succeeded()).isEqualTo(1);
        assertThat(repository.byId).doesNotContainKey(completed.getId());
    }

    @Test
    void confirmActiveOnAlreadyAutoCompletedSessionFailsWithoutSecondEvent() {
        UUID userId = UUID.randomUUID();
        Instant started = NOW.minus(ParkingSessionStalePolicy.defaults().autoCompleteAfter())
                .minusSeconds(60);
        ParkingSession session = ParkingSession.start(
                userId, ParkingSource.MANUAL, 41.0, 29.0, null, null, started);
        repository.save(session);
        assertThat(processor.tryAutoComplete(session.getId(), NOW)).isTrue();
        outbox.events.clear();

        assertThatThrownBy(() -> service.confirmActiveSession(userId, session.getId()))
                .isInstanceOf(ParkingException.class)
                .extracting(exception -> ((ParkingException) exception).errorCode())
                .isEqualTo(ParkingErrorCode.PARKING_SESSION_NOT_ACTIVE);
        assertThat(outbox.events).isEmpty();
    }

    private static final class FakeParkingSessionRepository implements ParkingSessionRepository {

        private final Map<UUID, ParkingSession> byId = new LinkedHashMap<>();

        @Override
        public ParkingSession save(ParkingSession session) {
            byId.put(session.getId(), session);
            return session;
        }

        @Override
        public Optional<ParkingSession> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
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
            throw new UnsupportedOperationException();
        }

        @Override
        public ParkingSessionHistoryPage findHistoryByUserId(
                UUID userId, ParkingSessionHistoryCursor cursor, int pageSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteTerminalByIdAndUserId(UUID id, UUID userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteAllTerminalByUserId(UUID userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ParkingSessionStatus> findStatusByIdAndUserId(UUID id, UUID userId) {
            return Optional.ofNullable(byId.get(id))
                    .filter(session -> session.getUserId().equals(userId))
                    .map(ParkingSession::getStatus);
        }

        @Override
        public List<ParkingSession> findStaleActiveCandidates(
                Instant confirmedAtOrBefore, Instant startedAtOrBefore, int limit) {
            return byId.values().stream()
                    .filter(ParkingSession::isActive)
                    .filter(session -> {
                        Instant confirmed = session.getLastConfirmedAt() != null
                                ? session.getLastConfirmedAt()
                                : session.getStartedAt();
                        return !confirmed.isAfter(confirmedAtOrBefore)
                                && !session.getStartedAt().isAfter(startedAtOrBefore);
                    })
                    .sorted(Comparator
                            .comparing((ParkingSession session) -> session.getLastConfirmedAt() != null
                                    ? session.getLastConfirmedAt()
                                    : session.getStartedAt())
                            .thenComparing(ParkingSession::getStartedAt)
                            .thenComparing(session -> session.getId().toString()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ParkingSession> findReminderCandidates(
                int currentReminderStage,
                Instant confirmedAtOrBefore,
                Instant startedAtOrBefore,
                int limit) {
            return byId.values().stream()
                    .filter(ParkingSession::isActive)
                    .filter(session -> session.getReminderStage().wireValue() == currentReminderStage)
                    .filter(session -> {
                        Instant confirmed = session.getLastConfirmedAt() != null
                                ? session.getLastConfirmedAt()
                                : session.getStartedAt();
                        return !confirmed.isAfter(confirmedAtOrBefore);
                    })
                    .filter(session -> startedAtOrBefore == null
                            || !session.getStartedAt().isAfter(startedAtOrBefore))
                    .sorted(Comparator
                            .comparing((ParkingSession session) -> session.getLastConfirmedAt() != null
                                    ? session.getLastConfirmedAt()
                                    : session.getStartedAt())
                            .thenComparing(ParkingSession::getStartedAt)
                            .thenComparing(session -> session.getId().toString()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long countByStatus(ParkingSessionStatus status) {
            return byId.values().stream().filter(session -> session.getStatus() == status).count();
        }

        @Override
        public int deleteTerminalEndedAtOrBefore(Instant endedAtOrBefore, int limit) {
            List<UUID> removable = byId.values().stream()
                    .filter(session -> !session.isActive())
                    .filter(session -> session.getEndedAt() != null
                            && !session.getEndedAt().isAfter(endedAtOrBefore))
                    .sorted(Comparator.comparing(ParkingSession::getEndedAt)
                            .thenComparing(session -> session.getId().toString()))
                    .limit(limit)
                    .map(ParkingSession::getId)
                    .toList();
            removable.forEach(byId::remove);
            return removable.size();
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
