package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.parkio.parking.application.ParkingSessionService;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSource;
import com.parkio.parking.presentation.dto.StartParkingSessionRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ParkingSessionControllerTest.FixedClockConfiguration.class)
class ParkingSessionControllerTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ParkingSessionService sessions;

    @BeforeEach
    void clearState() {
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM parking_sessions");
    }

    @Test
    void validStartReturnsFrozenResponseAndNoStore() throws Exception {
        start(UUID.randomUUID(), UUID.randomUUID().toString(),
                "{\"latitude\":41.0082,\"longitude\":28.9784,\"estimatedFee\":\"125.50\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.parkingSource").value("MANUAL"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.endedAt").value(nullValue()))
                .andExpect(jsonPath("$.latitude").value(41.0082))
                .andExpect(jsonPath("$.longitude").value(28.9784))
                .andExpect(jsonPath("$.estimatedFee").value("125.50"))
                .andExpect(jsonPath("$.lastConfirmedAt").value("2026-07-21T09:00:00Z"))
                .andExpect(jsonPath("$.completionType").value(nullValue()))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.reminderAt").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void lifecycleConfigReturnsConfiguredDurationsAndFlags() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/lifecycle-config"), userId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.confirmAfterMs").value(86_400_000))
                .andExpect(jsonPath("$.reminder2AfterMs").value(172_800_000))
                .andExpect(jsonPath("$.autoCompleteAfterMs").value(259_200_000))
                .andExpect(jsonPath("$.confirmAfter").value("PT24H"))
                .andExpect(jsonPath("$.reminder2After").value("PT48H"))
                .andExpect(jsonPath("$.autoCompleteAfter").value("PT72H"))
                .andExpect(jsonPath("$.remindersEnabled").value(true))
                .andExpect(jsonPath("$.autoCompleteEnabled").value(true));

        mockMvc.perform(get("/api/v1/parking/sessions/lifecycle-config")
                        .header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));
    }

    @Test
    void confirmActiveExtendsLastConfirmedAtWithoutOutboxEvent() throws Exception {
        UUID userId = UUID.randomUUID();
        String sessionId = startAndGetId(userId);
        int before = countOutbox("ParkingSessionCompletedEvent")
                + countOutbox("ParkingSessionStartedEvent")
                + countOutbox("ParkingSessionCancelledEvent");

        mockMvc.perform(authenticated(
                        post("/api/v1/parking/sessions/{sessionId}/confirm-active", sessionId), userId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.id").value(sessionId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.lastConfirmedAt").value("2026-07-21T09:00:00Z"))
                .andExpect(jsonPath("$.completionType").value(nullValue()));

        int after = countOutbox("ParkingSessionCompletedEvent")
                + countOutbox("ParkingSessionStartedEvent")
                + countOutbox("ParkingSessionCancelledEvent");
        assertThat(after).isEqualTo(before);
    }

    @Test
    void completePersistsManualCompletionType() throws Exception {
        UUID userId = UUID.randomUUID();
        String sessionId = startAndGetId(userId);

        transition(userId, sessionId, "complete", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completionType").value("MANUAL"));
    }

    @Test
    void omittedAndExplicitNullFeeBothRemainNull() throws Exception {
        start(UUID.randomUUID(), UUID.randomUUID().toString(),
                "{\"latitude\":41.0,\"longitude\":29.0}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estimatedFee").value(nullValue()));

        start(UUID.randomUUID(), UUID.randomUUID().toString(),
                "{\"latitude\":41.0,\"longitude\":29.0,\"estimatedFee\":null}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estimatedFee").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "1.2", "1.23", "01.00", "9999999999.99"})
    void acceptsFeeLexicalFormsAndNormalizesExactly(String fee) throws Exception {
        String expected = new BigDecimal(fee).setScale(2, RoundingMode.UNNECESSARY).toPlainString();

        start(UUID.randomUUID(), UUID.randomUUID().toString(), requestWithFee(fee))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estimatedFee").value(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-0.01", "+1.00", "1.234", "1e2", " 1.00", "", "10000000000.00"
    })
    void rejectsInvalidFeeLexicalForms(String fee) throws Exception {
        start(UUID.randomUUID(), UUID.randomUUID().toString(), requestWithFee(fee))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("estimatedFee"));
    }

    @Test
    void rejectsEstimatedFeeTextAboveTheDocumentedLexicalBound() throws Exception {
        String fee = "0".repeat(StartParkingSessionRequest.MAX_ESTIMATED_FEE_LENGTH + 1);

        start(UUID.randomUUID(), UUID.randomUUID().toString(), requestWithFee(fee))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("estimatedFee"));
    }

    @Test
    void rejectsNumericFeeAndClientControlledFields() throws Exception {
        start(UUID.randomUUID(), UUID.randomUUID().toString(),
                "{\"latitude\":41.0,\"longitude\":29.0,\"estimatedFee\":1.23}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        start(UUID.randomUUID(), UUID.randomUUID().toString(),
                "{\"latitude\":41.0,\"longitude\":29.0,\"parkingSource\":\"AUTO\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @ParameterizedTest
    @CsvSource({"-90.0,-180.0", "90.0,180.0"})
    void acceptsCoordinateBoundaries(double latitude, double longitude) throws Exception {
        start(UUID.randomUUID(), UUID.randomUUID().toString(),
                "{\"latitude\":" + latitude + ",\"longitude\":" + longitude + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.latitude").value(latitude))
                .andExpect(jsonPath("$.longitude").value(longitude));
    }

    @ParameterizedTest
    @CsvSource({"-90.01,0", "90.01,0", "0,-180.01", "0,180.01"})
    void rejectsOutOfRangeCoordinates(double latitude, double longitude) throws Exception {
        start(UUID.randomUUID(), UUID.randomUUID().toString(),
                "{\"latitude\":" + latitude + ",\"longitude\":" + longitude + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsMissingMalformedAndNonFiniteCoordinates() throws Exception {
        start(UUID.randomUUID(), UUID.randomUUID().toString(), "{\"longitude\":29.0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        start(UUID.randomUUID(), UUID.randomUUID().toString(),
                "{\"latitude\":\"41.0\",\"longitude\":29.0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        start(UUID.randomUUID(), UUID.randomUUID().toString(),
                "{\"latitude\":NaN,\"longitude\":29.0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        start(UUID.randomUUID(), UUID.randomUUID().toString(),
                "{\"latitude\":1e309,\"longitude\":29.0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void enforcesIdempotencyKeyAndNormalizedFingerprint() throws Exception {
        UUID userId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        start(userId, null, requestWithFee("1.20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        start(userId, "short", requestWithFee("1.20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));

        MvcResult first = start(userId, key, requestWithFee("1.2"))
                .andExpect(status().isCreated())
                .andReturn();
        String sessionId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        assertThat(countOutbox("ParkingSessionStarted")).isEqualTo(1);

        start(userId, key, requestWithFee("1.20"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(sessionId));
        assertThat(countOutbox("ParkingSessionStarted")).isEqualTo(1);
        start(userId, key, requestWithFee("1.21"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertThat(countOutbox("ParkingSessionStarted")).isEqualTo(1);
    }

    @Test
    void duplicateActiveSessionUsesStableConflict() throws Exception {
        UUID userId = UUID.randomUUID();
        start(userId, UUID.randomUUID().toString(), requestWithFee("1.00"))
                .andExpect(status().isCreated());
        assertThat(countOutbox("ParkingSessionStarted")).isEqualTo(1);

        start(userId, UUID.randomUUID().toString(), requestWithFee("2.00"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("ACTIVE_PARKING_SESSION_EXISTS"));
        assertThat(countOutbox("ParkingSessionStarted")).isEqualTo(1);
    }

    @Test
    void completeAndCancelPersistLifecycleOutboxEventsOncePerTransition() throws Exception {
        UUID owner = UUID.randomUUID();
        String completeKey = UUID.randomUUID().toString();
        String cancelKey = UUID.randomUUID().toString();

        String completedId = JsonPath.read(start(owner, UUID.randomUUID().toString(), requestWithFee("1.00"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(), "$.id");
        transition(owner, completedId, "complete", completeKey).andExpect(status().isOk());
        transition(owner, completedId, "complete", completeKey).andExpect(status().isOk());
        assertThat(countOutbox("ParkingSessionCompleted")).isEqualTo(1);

        String cancelledId = JsonPath.read(start(owner, UUID.randomUUID().toString(), requestWithFee("2.00"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(), "$.id");
        transition(owner, cancelledId, "cancel", cancelKey).andExpect(status().isOk());
        transition(owner, cancelledId, "cancel", cancelKey).andExpect(status().isOk());
        assertThat(countOutbox("ParkingSessionCancelled")).isEqualTo(1);

        String payload = jdbc.queryForObject("""
                SELECT payload FROM outbox_events
                 WHERE event_type = 'ParkingSessionCompleted'
                 LIMIT 1
                """, String.class);
        assertThat(payload).doesNotContain("latitude");
        assertThat(payload).doesNotContain("longitude");
        assertThat(payload).doesNotContain("idempotency");
    }

    @Test
    void activeEndpointReturnsSessionOrEmptyNoContent() throws Exception {
        UUID userWithSession = UUID.randomUUID();
        String id = JsonPath.read(start(
                        userWithSession, UUID.randomUUID().toString(), requestWithFee("5.00"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/active"), userWithSession))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.id").value(id));

        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/active"), UUID.randomUUID()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""));
    }

    @Test
    void completeIsRetrySafeAndTerminalTransitionsConflictWithNewKeys() throws Exception {
        UUID userId = UUID.randomUUID();
        String sessionId = startAndGetId(userId);
        String key = UUID.randomUUID().toString();

        MvcResult first = transition(userId, sessionId, "complete", key)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.endedAt").isNotEmpty())
                .andReturn();
        String endedAt = JsonPath.read(first.getResponse().getContentAsString(), "$.endedAt");

        transition(userId, sessionId, "complete", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endedAt").value(endedAt));
        transition(userId, sessionId, "complete", UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARKING_SESSION_NOT_ACTIVE"));
        transition(userId, sessionId, "cancel", UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARKING_SESSION_NOT_ACTIVE"));
    }

    @Test
    void cancelAndOwnershipSafeLookupUseFrozenStatuses() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        String sessionId = startAndGetId(owner);

        transition(otherUser, sessionId, "complete", UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARKING_SESSION_NOT_FOUND"));
        transition(owner, sessionId, "cancel", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        transition(owner, UUID.randomUUID().toString(), "cancel", UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARKING_SESSION_NOT_FOUND"));
    }

    @Test
    void malformedSessionIdUsesEstablishedMalformedRequestEnvelope() throws Exception {
        transition(UUID.randomUUID(), "not-a-uuid", "complete", UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void historyUsesDefaultBoundAndOpaqueKeysetContinuation() throws Exception {
        UUID userId = UUID.randomUUID();
        for (int index = 0; index < 21; index++) {
            terminalSession(userId, index % 2 == 0);
        }
        sessions.startSession(userId, ParkingSource.MANUAL, 41.0, 29.0, null, null);

        MvcResult first = mockMvc.perform(authenticated(
                        get("/api/v1/parking/sessions/history"), userId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[?(@.status == 'ACTIVE')]").isEmpty())
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        String cursor = JsonPath.read(first.getResponse().getContentAsString(), "$.nextCursor");

        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/history"), userId)
                        .param("size", "20")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void historyValidatesSizeCursorAndEmptyEnvelope() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/history"), userId)
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/history"), userId)
                        .param("size", "100"))
                .andExpect(status().isOk());
        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/history"), userId)
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/history"), userId)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/history"), userId)
                        .param("cursor", "not+base64"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_PARKING_SESSION_CURSOR"));
    }

    @Test
    void authenticatedIdentityFailsClosedWhenMissingOrMalformed() throws Exception {
        mockMvc.perform(post("/api/v1/parking/sessions")
                        .header("X-User-Id", UUID.randomUUID())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":41.0,\"longitude\":29.0}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("GATEWAY_AUTH_REQUIRED"));
        mockMvc.perform(post("/api/v1/parking/sessions")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":41.0,\"longitude\":29.0}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));
        mockMvc.perform(post("/api/v1/parking/sessions")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("X-User-Id", "not-a-uuid")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":41.0,\"longitude\":29.0}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));
    }

    @Test
    void deleteTerminalSessionIsOwnerSafeIdempotentAndRejectsActive() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        String completedId = startAndGetId(owner);
        transition(owner, completedId, "complete", UUID.randomUUID().toString())
                .andExpect(status().isOk());
        String activeId = startAndGetId(owner);
        String foreignId = startAndGetId(stranger);
        transition(stranger, foreignId, "complete", UUID.randomUUID().toString())
                .andExpect(status().isOk());

        mockMvc.perform(authenticated(delete("/api/v1/parking/sessions/{sessionId}", completedId), owner))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""));
        mockMvc.perform(authenticated(delete("/api/v1/parking/sessions/{sessionId}", completedId), owner))
                .andExpect(status().isNoContent());
        mockMvc.perform(authenticated(
                        delete("/api/v1/parking/sessions/{sessionId}", UUID.randomUUID()), owner))
                .andExpect(status().isNoContent());
        mockMvc.perform(authenticated(delete("/api/v1/parking/sessions/{sessionId}", foreignId), owner))
                .andExpect(status().isNoContent());
        mockMvc.perform(authenticated(delete("/api/v1/parking/sessions/{sessionId}", activeId), stranger))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE id = ?",
                Integer.class,
                UUID.fromString(foreignId))).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE id = ?",
                Integer.class,
                UUID.fromString(activeId))).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE id = ?",
                Integer.class,
                UUID.fromString(completedId))).isEqualTo(0);

        mockMvc.perform(authenticated(delete("/api/v1/parking/sessions/{sessionId}", activeId), owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARKING_SESSION_NOT_TERMINAL"))
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andExpect(jsonPath("$.longitude").doesNotExist());
        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/active"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activeId));
        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/history"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void deleteHistoryClearsOwnedTerminalsAndPreservesActive() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        terminalSession(owner, true);
        terminalSession(owner, false);
        String activeId = startAndGetId(owner);
        terminalSession(stranger, true);

        mockMvc.perform(authenticated(delete("/api/v1/parking/sessions/history"), owner))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(authenticated(delete("/api/v1/parking/sessions/history"), owner))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/parking/sessions/history")
                        .header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));

        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/active"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activeId));
        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/history"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/history"), stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class,
                owner)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM parking_sessions
                WHERE user_id = ? AND status IN ('COMPLETED', 'CANCELLED')
                """,
                Integer.class,
                owner)).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM parking_sessions
                WHERE user_id = ? AND status IN ('COMPLETED', 'CANCELLED')
                """,
                Integer.class,
                stranger)).isEqualTo(1);
    }

    private String startAndGetId(UUID userId) throws Exception {
        MvcResult result = start(
                        userId,
                        UUID.randomUUID().toString(),
                        "{\"latitude\":41.0,\"longitude\":29.0}")
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private void terminalSession(UUID userId, boolean complete) {
        ParkingSession session = sessions.startSession(
                userId, ParkingSource.MANUAL, 41.0, 29.0, null, null);
        if (complete) {
            sessions.completeSession(userId, session.getId());
        } else {
            sessions.cancelSession(userId, session.getId());
        }
    }

    private ResultActions start(UUID userId, String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = authenticated(post("/api/v1/parking/sessions"), userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private ResultActions transition(
            UUID userId, String sessionId, String action, String key) throws Exception {
        return mockMvc.perform(authenticated(
                        post("/api/v1/parking/sessions/{sessionId}/{action}", sessionId, action), userId)
                .header("Idempotency-Key", key));
    }

    private static MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request, UUID userId) {
        return request.header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("X-User-Id", userId);
    }

    private static String requestWithFee(String fee) {
        return "{\"latitude\":41.0,\"longitude\":29.0,\"estimatedFee\":\"" + fee + "\"}";
    }

    private int countOutbox(String eventType) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = ?",
                Integer.class,
                eventType);
        return count == null ? 0 : count;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock parkingSessionTestClock() {
            return Clock.fixed(Instant.parse("2026-07-21T09:00:00Z"), ZoneOffset.UTC);
        }
    }
}
