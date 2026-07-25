package com.parkio.parking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParkingSessionStalePolicyTest {

    private static final Instant STARTED = Instant.parse("2026-07-21T09:00:00Z");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final ParkingSessionStalePolicy POLICY = ParkingSessionStalePolicy.defaults();

    @Test
    void needsConfirmationAfterTwentyFourHours() {
        ParkingSession session = ParkingSession.start(
                USER, ParkingSource.MANUAL, 41.0, 29.0, null, null, STARTED);

        assertThat(POLICY.needsConfirmation(
                session, STARTED.plus(23, ChronoUnit.HOURS).plus(59, ChronoUnit.MINUTES)))
                .isFalse();
        assertThat(POLICY.needsConfirmation(
                session, STARTED.plus(24, ChronoUnit.HOURS)))
                .isTrue();
    }

    @Test
    void confirmationResetsWarningWindow() {
        ParkingSession session = ParkingSession.start(
                USER, ParkingSource.MANUAL, 41.0, 29.0, null, null, STARTED);
        Instant confirmed = STARTED.plus(30, ChronoUnit.HOURS);
        session.confirmActive(confirmed);

        assertThat(POLICY.needsConfirmation(
                session, confirmed.plus(23, ChronoUnit.HOURS)))
                .isFalse();
        assertThat(POLICY.needsConfirmation(
                session, confirmed.plus(24, ChronoUnit.HOURS)))
                .isTrue();
    }

    @Test
    void autoCompleteRequiresBothStartedAndConfirmedOlderThanSeventyTwoHours() {
        ParkingSession session = ParkingSession.start(
                USER, ParkingSource.MANUAL, 41.0, 29.0, null, null, STARTED);
        Instant midConfirm = STARTED.plus(50, ChronoUnit.HOURS);
        session.confirmActive(midConfirm);

        Instant now = STARTED.plus(73, ChronoUnit.HOURS);
        assertThat(POLICY.isEligibleForAutoComplete(session, now)).isFalse();

        Instant later = midConfirm.plus(72, ChronoUnit.HOURS);
        assertThat(POLICY.isEligibleForAutoComplete(session, later)).isTrue();
    }

    @Test
    void multipleConfirmationsKeepExtendingTheWindow() {
        ParkingSession session = ParkingSession.start(
                USER, ParkingSource.MANUAL, 41.0, 29.0, null, null, STARTED);
        Instant first = STARTED.plus(24, ChronoUnit.HOURS);
        Instant second = first.plus(24, ChronoUnit.HOURS);
        session.confirmActive(first);
        session.confirmActive(second);

        assertThat(session.getLastConfirmedAt()).isEqualTo(second);
        assertThat(POLICY.needsConfirmation(
                session, second.plus(23, ChronoUnit.HOURS)))
                .isFalse();
    }

    @Test
    void nextReminderStageAdvancesWithoutDuplicates() {
        ParkingSession session = ParkingSession.start(
                USER, ParkingSource.MANUAL, 41.0, 29.0, null, null, STARTED);

        Instant at24h = STARTED.plus(24, ChronoUnit.HOURS);
        assertThat(POLICY.nextReminderStage(session, at24h))
                .contains(ParkingSessionReminderStage.FIRST);

        session.markReminderSent(ParkingSessionReminderStage.FIRST, at24h);
        assertThat(POLICY.nextReminderStage(session, at24h)).isEmpty();

        Instant at48h = STARTED.plus(48, ChronoUnit.HOURS);
        assertThat(POLICY.nextReminderStage(session, at48h))
                .contains(ParkingSessionReminderStage.SECOND);

        session.markReminderSent(ParkingSessionReminderStage.SECOND, at48h);
        assertThat(POLICY.nextReminderStage(session, at48h)).isEmpty();
    }

    @Test
    void rejectsEqualOrInvertedDurationWindows() {
        assertThatThrownBy(() -> new ParkingSessionStalePolicy(
                Duration.ofHours(24), Duration.ofHours(24), Duration.ofHours(72)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParkingSessionStalePolicy(
                Duration.ofHours(48), Duration.ofHours(24), Duration.ofHours(72)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParkingSessionStalePolicy(
                Duration.ofHours(24), Duration.ofHours(72), Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParkingSessionStalePolicy(
                Duration.ZERO, Duration.ofHours(48), Duration.ofHours(72)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}