package com.parkio.parking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ParkingSessionTest {

    private static final Instant STARTED_AT = Instant.parse("2026-07-21T09:00:00Z");
    private static final UUID USER_ID = UUID.fromString("f7c7c3d4-681c-4eed-8b80-7c77de35b7fc");

    @Test
    void createsValidActiveSession() {
        Instant reminderAt = STARTED_AT.plus(2, ChronoUnit.HOURS);

        ParkingSession session = ParkingSession.start(
                USER_ID, ParkingSource.MANUAL, 41.0082, 28.9784,
                new BigDecimal("125.50"), reminderAt, STARTED_AT);

        assertThat(session.getId()).isNotNull();
        assertThat(session.getUserId()).isEqualTo(USER_ID);
        assertThat(session.getStatus()).isEqualTo(ParkingSessionStatus.ACTIVE);
        assertThat(session.getParkingSource()).isEqualTo(ParkingSource.MANUAL);
        assertThat(session.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(session.getEndedAt()).isNull();
        assertThat(session.getLatitude()).isEqualTo(41.0082);
        assertThat(session.getLongitude()).isEqualTo(28.9784);
        assertThat(session.getEstimatedFee()).isEqualByComparingTo("125.50");
        assertThat(session.getReminderAt()).isEqualTo(reminderAt);
        assertThat(session.getCreatedAt()).isEqualTo(STARTED_AT);
        assertThat(session.getUpdatedAt()).isEqualTo(STARTED_AT);
        assertThat(session.getVersion()).isNull();
        assertThat(session.isActive()).isTrue();
    }

    @Test
    void completesActiveSession() {
        ParkingSession session = newSession();
        Instant endedAt = STARTED_AT.plus(90, ChronoUnit.MINUTES);

        session.complete(endedAt);

        assertThat(session.getStatus()).isEqualTo(ParkingSessionStatus.COMPLETED);
        assertThat(session.getEndedAt()).isEqualTo(endedAt);
        assertThat(session.getUpdatedAt()).isEqualTo(endedAt);
        assertThat(session.isActive()).isFalse();
    }

    @Test
    void cancelsActiveSession() {
        ParkingSession session = newSession();
        Instant endedAt = STARTED_AT.plus(5, ChronoUnit.MINUTES);

        session.cancel(endedAt);

        assertThat(session.getStatus()).isEqualTo(ParkingSessionStatus.CANCELLED);
        assertThat(session.getEndedAt()).isEqualTo(endedAt);
        assertThat(session.getUpdatedAt()).isEqualTo(endedAt);
        assertThat(session.isActive()).isFalse();
    }

    @Test
    void rejectsTransitionsFromCompletedSession() {
        ParkingSession session = newSession();
        session.complete(STARTED_AT.plusSeconds(1));

        assertInvalidTransition(() -> session.complete(STARTED_AT.plusSeconds(2)));
        assertInvalidTransition(() -> session.cancel(STARTED_AT.plusSeconds(2)));
    }

    @Test
    void rejectsTransitionsFromCancelledSession() {
        ParkingSession session = newSession();
        session.cancel(STARTED_AT.plusSeconds(1));

        assertInvalidTransition(() -> session.complete(STARTED_AT.plusSeconds(2)));
        assertInvalidTransition(() -> session.cancel(STARTED_AT.plusSeconds(2)));
    }

    @Test
    void rejectsEndedAtBeforeStartedAtWithoutChangingActiveState() {
        ParkingSession session = newSession();

        assertThatThrownBy(() -> session.complete(STARTED_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endedAt cannot be before startedAt");

        assertThat(session.isActive()).isTrue();
        assertThat(session.getStatus()).isEqualTo(ParkingSessionStatus.ACTIVE);
        assertThat(session.getEndedAt()).isNull();
        assertThat(session.getUpdatedAt()).isEqualTo(STARTED_AT);
    }

    @Test
    void rejectsInvalidLocation() {
        assertThatThrownBy(() -> ParkingSession.start(
                USER_ID, ParkingSource.AUTO, Double.NaN, 28.9784, null, null, STARTED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("latitude must be between -90 and 90");

        assertThatThrownBy(() -> ParkingSession.start(
                USER_ID, ParkingSource.AUTO, 41.0082, Double.POSITIVE_INFINITY,
                null, null, STARTED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("longitude must be between -180 and 180");
    }

    @ParameterizedTest
    @CsvSource({"-90.0, -180.0", "0.0, 0.0", "90.0, 180.0"})
    void acceptsBoundaryLocations(double latitude, double longitude) {
        ParkingSession session = ParkingSession.start(
                USER_ID, ParkingSource.MANUAL, latitude, longitude, null, null, STARTED_AT);

        assertThat(session.getLatitude()).isEqualTo(latitude);
        assertThat(session.getLongitude()).isEqualTo(longitude);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "1.2", "1.23", "1.2300", "9999999999.99"})
    void acceptsEstimatedFeesExactlyRepresentableAsNumericTwelveTwo(String value) {
        ParkingSession session = newSessionWithEstimatedFee(new BigDecimal(value));

        assertThat(session.getEstimatedFee()).isEqualByComparingTo(value);
        assertThat(session.getEstimatedFee()).hasScaleOf(2);
    }

    @Test
    void acceptsNullEstimatedFee() {
        ParkingSession session = newSessionWithEstimatedFee(null);

        assertThat(session.getEstimatedFee()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.234", "9999999999.999"})
    void rejectsEstimatedFeesThatRequireRounding(String value) {
        assertThatThrownBy(() -> newSessionWithEstimatedFee(new BigDecimal(value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("estimatedFee must be representable exactly with at most 2 decimal places");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000000000", "10000000000.00", "99999999999.99"})
    void rejectsEstimatedFeesExceedingNumericTwelveTwo(String value) {
        assertThatThrownBy(() -> newSessionWithEstimatedFee(new BigDecimal(value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("estimatedFee exceeds NUMERIC(12,2)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "-1", "-9999999999.99"})
    void rejectsNegativeEstimatedFees(String value) {
        assertThatThrownBy(() -> newSessionWithEstimatedFee(new BigDecimal(value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("estimatedFee cannot be negative");
    }

    private static ParkingSession newSession() {
        return ParkingSession.start(
                USER_ID, ParkingSource.COMMUNITY, 41.0082, 28.9784,
                null, null, STARTED_AT);
    }

    private static ParkingSession newSessionWithEstimatedFee(BigDecimal estimatedFee) {
        return ParkingSession.start(
                USER_ID, ParkingSource.MANUAL, 41.0082, 28.9784,
                estimatedFee, null, STARTED_AT);
    }

    private static void assertInvalidTransition(Runnable transition) {
        assertThatThrownBy(transition::run)
                .isInstanceOf(ParkingException.class)
                .extracting(exception -> ((ParkingException) exception).errorCode())
                .isEqualTo(ParkingErrorCode.PARKING_SESSION_NOT_ACTIVE);
    }
}
