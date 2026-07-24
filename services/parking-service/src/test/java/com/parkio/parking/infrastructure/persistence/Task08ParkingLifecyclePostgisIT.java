package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.ParkingApplicationService;
import com.parkio.parking.application.port.MediaReadinessPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Task 8 verification gate: representative parking lifecycle against real PostGIS.
 * Complements H2 MockMvc idempotency tests and session concurrency ITs with an
 * explicit create → AI pass → verify → claim path, persistence, invalid
 * transitions, idempotent replay, and a two-claimer race on PostgreSQL.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class Task08ParkingLifecyclePostgisIT {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres");
    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_parking_task08_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGIS::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.kafka.ai-validation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ParkingApplicationService parking;

    @MockBean
    private MediaReadinessPort mediaReadiness;

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM parking_spot_verifications");
        jdbc.update("DELETE FROM parking_spot_status_history");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM parking_sessions");
        jdbc.update("DELETE FROM parking_spots");
    }

    @Test
    void successfulLifecyclePersistsCreateVerifyAndClaimOnPostgres() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID verifier = UUID.randomUUID();
        UUID claimer = UUID.randomUUID();

        UUID spotId = createdSpotId(owner);
        assertThat(count("parking_spots")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM parking_spots WHERE id = ?", String.class, spotId))
                .isEqualTo("ACTIVE");

        verify(verifier, spotId, UUID.randomUUID().toString(), "AVAILABLE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));
        assertThat(count("parking_spot_verifications")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM parking_spots WHERE id = ?", String.class, spotId))
                .isEqualTo("VERIFIED");

        claim(claimer, spotId, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"));
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM parking_spots WHERE id = ?", String.class, spotId))
                .isEqualTo("FILLED");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM parking_sessions
                        WHERE user_id = ? AND parking_source = 'COMMUNITY' AND status = 'ACTIVE'
                        """,
                        Integer.class,
                        claimer))
                .isEqualTo(1);
    }

    @Test
    void invalidTransitionIsRejectedWithoutCorruptingPersistedSpot() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID spotId = createdSpotId(owner);

        // Owner cannot claim their own spot (forbidden domain rule).
        claim(owner, spotId, UUID.randomUUID().toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_CANNOT_CLAIM"));

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM parking_spots WHERE id = ?", String.class, spotId))
                .isEqualTo("ACTIVE");
        assertThat(count("parking_sessions")).isEqualTo(0);
    }

    @Test
    void idempotentCreateReplayDoesNotDuplicateRows() throws Exception {
        UUID userId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String body = createBody(UUID.randomUUID(), "task08-idempotent");

        MvcResult first = create(userId, key, body);
        String spotId = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(authenticated(post("/api/v1/parking/spots"), userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(spotId));

        assertThat(count("parking_spots")).isEqualTo(1);
        assertThat(count("parking_spot_status_history")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void duplicateSubmissionWithDifferentBodyConflicts() throws Exception {
        UUID userId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        create(userId, key, createBody(UUID.randomUUID(), "first"));
        mockMvc.perform(authenticated(post("/api/v1/parking/spots"), userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), "different")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        assertThat(count("parking_spots")).isEqualTo(1);
    }

    @Test
    void concurrentClaimsCommitExactlyOneActiveCommunitySession() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID claimerA = UUID.randomUUID();
        UUID claimerB = UUID.randomUUID();
        UUID spotId = createdSpotId(owner);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        try {
            Future<Integer> a = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                int status = claim(claimerA, spotId, UUID.randomUUID().toString())
                        .andReturn()
                        .getResponse()
                        .getStatus();
                done.countDown();
                return status;
            });
            Future<Integer> b = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                int status = claim(claimerB, spotId, UUID.randomUUID().toString())
                        .andReturn()
                        .getResponse()
                        .getStatus();
                done.countDown();
                return status;
            });

            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            int statusA = a.get(5, TimeUnit.SECONDS);
            int statusB = b.get(5, TimeUnit.SECONDS);
            assertThat(List.of(statusA, statusB)).contains(200);
            assertThat(statusA == 200 && statusB == 200).isFalse();

            assertThat(jdbc.queryForObject(
                            "SELECT status FROM parking_spots WHERE id = ?", String.class, spotId))
                    .isEqualTo("FILLED");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT count(*) FROM parking_sessions
                            WHERE status = 'ACTIVE' AND parking_source = 'COMMUNITY'
                            """,
                            Integer.class))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private MvcResult create(UUID userId, String key, String body) throws Exception {
        return mockMvc.perform(authenticated(post("/api/v1/parking/spots"), userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private UUID createdSpotId(UUID owner) throws Exception {
        MvcResult result = create(
                owner, UUID.randomUUID().toString(), createBody(UUID.randomUUID(), "task08-setup"));
        UUID spotId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id"));
        parking.applyAiValidationResult(spotId, "PASSED", List.of());
        return spotId;
    }

    private org.springframework.test.web.servlet.ResultActions claim(
            UUID userId, UUID spotId, String key) throws Exception {
        return mockMvc.perform(authenticated(
                        post("/api/v1/parking/spots/{spotId}/claim", spotId), userId)
                .header("Idempotency-Key", key));
    }

    private org.springframework.test.web.servlet.ResultActions verify(
            UUID userId, UUID spotId, String key, String result) throws Exception {
        return mockMvc.perform(authenticated(
                        post("/api/v1/parking/spots/{spotId}/verify", spotId), userId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"" + result + "\"}"));
    }

    private MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request, UUID userId) {
        return request.header("X-Gateway-Auth", GATEWAY_SECRET).header("X-User-Id", userId);
    }

    private String createBody(UUID mediaId, String description) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "mediaId", mediaId,
                "latitude", 41.0082,
                "longitude", 28.9784,
                "description", description,
                "manualLocationEdited", false,
                "suitableVehicleTypes", new String[] {"SEDAN"},
                "parkingContext", "STREET_PARKING",
                "legalStatus", "LEGAL",
                "violationReasons", new String[0]));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
