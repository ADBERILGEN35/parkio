package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSource;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.persistence.jpa.ParkingSessionJpaRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

class ParkingSessionRepositoryAdapterTest {

    @Test
    void translatesOnlyTheAuthoritativeActiveSessionIndex() {
        ParkingSessionJpaRepository jpa = mock(ParkingSessionJpaRepository.class);
        ParkingSessionRepositoryAdapter adapter = new ParkingSessionRepositoryAdapter(jpa);
        ParkingSession session = session();
        when(jpa.saveAndFlush(session)).thenThrow(integrityViolation(
                ParkingSessionRepositoryAdapter.ACTIVE_SESSION_UNIQUE_INDEX));

        assertThatThrownBy(() -> adapter.save(session))
                .isInstanceOf(ParkingException.class)
                .extracting(exception -> ((ParkingException) exception).errorCode())
                .isEqualTo(ParkingErrorCode.ACTIVE_PARKING_SESSION_EXISTS);

        verify(jpa).saveAndFlush(session);
    }

    @Test
    void preservesUnrelatedIntegrityViolations() {
        ParkingSessionJpaRepository jpa = mock(ParkingSessionJpaRepository.class);
        ParkingSessionRepositoryAdapter adapter = new ParkingSessionRepositoryAdapter(jpa);
        ParkingSession session = session();
        DataIntegrityViolationException failure = integrityViolation("some_other_constraint");
        when(jpa.saveAndFlush(session)).thenThrow(failure);

        assertThatThrownBy(() -> adapter.save(session)).isSameAs(failure);
    }

    @Test
    void reminderCandidatesWithoutStartCeilingUseTheNonNullableQueryVariant() {
        ParkingSessionJpaRepository jpa = mock(ParkingSessionJpaRepository.class);
        ParkingSessionRepositoryAdapter adapter = new ParkingSessionRepositoryAdapter(jpa);
        ParkingSession session = session();
        Instant confirmedThreshold = Instant.parse("2026-07-22T09:00:00Z");
        PageRequest page = PageRequest.of(0, 25);
        when(jpa.findReminderCandidates(0, confirmedThreshold, page))
                .thenReturn(new SliceImpl<>(List.of(session)));

        assertThat(adapter.findReminderCandidates(0, confirmedThreshold, null, 25))
                .containsExactly(session);

        verify(jpa).findReminderCandidates(0, confirmedThreshold, page);
        verify(jpa, never()).findReminderCandidatesStartedAtOrBefore(
                0, confirmedThreshold, confirmedThreshold, page);
    }

    @Test
    void reminderCandidatesWithStartCeilingUseTheBoundedQueryVariant() {
        ParkingSessionJpaRepository jpa = mock(ParkingSessionJpaRepository.class);
        ParkingSessionRepositoryAdapter adapter = new ParkingSessionRepositoryAdapter(jpa);
        ParkingSession session = session();
        Instant confirmedThreshold = Instant.parse("2026-07-22T09:00:00Z");
        Instant startedThreshold = Instant.parse("2026-07-21T09:00:00Z");
        PageRequest page = PageRequest.of(0, 25);
        when(jpa.findReminderCandidatesStartedAtOrBefore(
                        1, confirmedThreshold, startedThreshold, page))
                .thenReturn(new SliceImpl<>(List.of(session)));

        assertThat(adapter.findReminderCandidates(
                        1, confirmedThreshold, startedThreshold, 25))
                .containsExactly(session);

        verify(jpa).findReminderCandidatesStartedAtOrBefore(
                1, confirmedThreshold, startedThreshold, page);
        verify(jpa, never()).findReminderCandidates(1, confirmedThreshold, page);
    }

    private static DataIntegrityViolationException integrityViolation(String constraintName) {
        ConstraintViolationException hibernateFailure = new ConstraintViolationException(
                "constraint violation", new SQLException("duplicate", "23505"), constraintName);
        return new DataIntegrityViolationException("integrity violation", hibernateFailure);
    }

    private static ParkingSession session() {
        return ParkingSession.start(
                UUID.randomUUID(), ParkingSource.MANUAL,
                41.0082, 28.9784, null, null,
                Instant.parse("2026-07-21T09:00:00Z"));
    }
}
