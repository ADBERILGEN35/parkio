package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.platform.api.ApiError;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class GlobalExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-22T09:00:00Z");
    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void knownConcurrencyConstraintRemainsAGenericConflict() {
        ResponseEntity<ApiError> response = handler.handleIntegrityViolation(
                integrityViolation("uq_parking_spot_verifications_spot_user"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
        assertThat(response.getBody().message())
                .isEqualTo("The request conflicts with the current state of the resource.")
                .doesNotContain("constraint", "duplicate", "SQL");
    }

    @Test
    void unexpectedIntegrityViolationUsesGenericInternalError() {
        ResponseEntity<ApiError> response = handler.handleIntegrityViolation(
                integrityViolation("fk_unexpected_internal_constraint"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message())
                .isEqualTo("An unexpected error occurred.")
                .doesNotContain("constraint", "duplicate", "SQL");
    }

    @Test
    void optimisticLockFailureRemainsAGenericConflict() {
        ResponseEntity<ApiError> response = handler.handleOptimisticConflict(
                new ObjectOptimisticLockingFailureException("ParkingSpot", "spot-id"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
    }

    private static DataIntegrityViolationException integrityViolation(String constraintName) {
        ConstraintViolationException hibernateFailure = new ConstraintViolationException(
                "SQL duplicate details",
                new SQLException("duplicate key value", "23505"),
                constraintName);
        return new DataIntegrityViolationException("internal constraint failure", hibernateFailure);
    }
}
