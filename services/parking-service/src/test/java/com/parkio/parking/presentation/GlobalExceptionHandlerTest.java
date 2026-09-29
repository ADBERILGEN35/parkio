package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.metrics.MunicipalSourceMetrics;
import com.parkio.platform.api.ApiError;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * HTTP status ownership for parking-service: gated routes and MVC exceptions must not
 * collapse into INTERNAL_ERROR / 500 (DATA-WP-02D).
 */
class GlobalExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-22T09:00:00Z");
    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        MunicipalSourceProperties properties = mock(MunicipalSourceProperties.class);
        MunicipalSourceProperties.Izum izum = mock(MunicipalSourceProperties.Izum.class);
        when(properties.getIzum()).thenReturn(izum);
        when(izum.isEnabled()).thenReturn(true);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MunicipalManualSyncController(
                        mock(MunicipalFacilitySyncService.class),
                        mock(MunicipalSourceMetrics.class),
                        properties))
                .setControllerAdvice(handler)
                .build();
    }

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

    @Test
    void responseStatusExceptionPreservesForbidden() {
        ResponseEntity<ApiError> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
        assertThat(response.getBody().message()).isEqualTo("Admin role required");
        assertThat(response.getBody().message()).doesNotContain("Exception", "at com.");
    }

    @Test
    void noResourceFoundMapsToNotFound() {
        ResponseEntity<ApiError> response = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.POST,
                        "api/v1/parking/municipal/sources/osm-geofabrik-turkey/import"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("Resource not found.");
        assertThat(response.getBody().message()).doesNotContain("osm-geofabrik", "import");
    }

    @Test
    void methodNotAllowedMapsTo405() {
        ResponseEntity<ApiError> response = handler.handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("PUT"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void domainConflictMapsTo409() {
        ResponseEntity<ApiError> response = handler.handleParking(
                new ParkingException(ParkingErrorCode.ALREADY_VERIFIED));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("ALREADY_VERIFIED");
    }

    @Test
    void malformedBodyMapsTo400() {
        ResponseEntity<ApiError> response = handler.handleBadRequest(
                new HttpMessageNotReadableException("JSON parse error: secret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(response.getBody().message()).doesNotContain("secret", "JSON parse");
    }

    @Test
    void unexpectedExceptionMapsTo500WithoutLeak() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new RuntimeException("jdbc:postgresql://internal/db password=hunter2"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message())
                .isEqualTo("An unexpected error occurred.")
                .doesNotContain("jdbc", "password", "hunter2");
        assertThat(response.getBody().traceId()).isNotBlank();
    }

    @Test
    void nonAdminManualSyncIsForbiddenNotInternalError() throws Exception {
        mockMvc.perform(post("/api/v1/parking/municipal/sources/izmir-izum-otoparklar/sync")
                        .header("X-User-Roles", "USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Admin role required"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void adminManualSyncWhenIzumDisabledIsConflictNotInternalError() throws Exception {
        MunicipalSourceProperties properties = mock(MunicipalSourceProperties.class);
        MunicipalSourceProperties.Izum izum = mock(MunicipalSourceProperties.Izum.class);
        when(properties.getIzum()).thenReturn(izum);
        when(izum.isEnabled()).thenReturn(false);
        MockMvc disabled = MockMvcBuilders
                .standaloneSetup(new MunicipalManualSyncController(
                        mock(MunicipalFacilitySyncService.class),
                        mock(MunicipalSourceMetrics.class),
                        properties))
                .setControllerAdvice(handler)
                .build();

        disabled.perform(post("/api/v1/parking/municipal/sources/izmir-izum-otoparklar/sync")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("IZUM source is disabled"));
    }

    @Test
    void unknownPathThroughAdviceIsNotFound() throws Exception {
        // Standalone MockMvc returns empty 404 for unmapped paths; exercise the handler
        // path that ConditionalOnProperty absences hit in a full DispatcherServlet.
        ResponseEntity<ApiError> response = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "api/v1/parking/does-not-exist"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void wrongMethodOnMappedPathIsMethodNotAllowed() throws Exception {
        mockMvc.perform(put("/api/v1/parking/municipal/sources/izmir-izum-otoparklar/sync")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void getOnSyncPathIsMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/parking/municipal/sources/izmir-izum-otoparklar/sync")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isMethodNotAllowed());
    }

    private static DataIntegrityViolationException integrityViolation(String constraintName) {
        ConstraintViolationException hibernateFailure = new ConstraintViolationException(
                "SQL duplicate details",
                new SQLException("duplicate key value", "23505"),
                constraintName);
        return new DataIntegrityViolationException("internal constraint failure", hibernateFailure);
    }
}
