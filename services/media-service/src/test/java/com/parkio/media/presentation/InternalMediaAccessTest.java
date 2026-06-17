package com.parkio.media.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.parkio.media.application.port.MediaStoragePort;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP-contract tests for the service-to-service access-URL endpoint
 * ({@code POST /internal/media/{mediaId}/access-url}). The endpoint requires the
 * shared {@code X-Gateway-Auth} secret but performs no ownership check — the
 * calling service (parking-service) has already authorized the requester. The
 * gateway routes only {@code /api/v1/**}, so this path is never publicly reachable.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalMediaAccessTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";
    private static final byte[] JPEG = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private MediaStoragePort storage;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM media_validation_results");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM media_files");
        reset(storage);
        when(storage.store(anyString(), any(byte[].class), anyString()))
                .thenAnswer(invocation -> new MediaStoragePort.StoredObject(
                        "test-bucket", invocation.getArgument(0)));
        when(storage.generatePresignedGetUrl(anyString(), any(Duration.class)))
                .thenAnswer(invocation ->
                        "http://minio.local/presigned/" + UUID.randomUUID() + "?X-Amz-Signature=test");
    }

    @Test
    void internalAccessUrlRequiresGatewayAuth() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);

        mockMvc.perform(post("/internal/media/" + mediaId + "/access-url"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GATEWAY_AUTH_REQUIRED"));
    }

    @Test
    void internalAccessUrlSkipsOwnershipCheckForTrustedCaller() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);
        UUID someOtherUser = UUID.randomUUID();

        // No X-User-Id header and a requester who is not the owner: the trusted
        // internal caller already authorized the request (spot visibility).
        MvcResult result = mockMvc.perform(post("/internal/media/" + mediaId + "/access-url")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requesterUserId\":\"" + someOtherUser + "\",\"purpose\":\"SPOT_PHOTO_VIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaId").value(mediaId))
                .andExpect(jsonPath("$.accessUrl").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("bucket", "objectKey", "object_key", "checksum");
    }

    @Test
    void internalAccessUrlWorksWithoutBody() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);

        mockMvc.perform(post("/internal/media/" + mediaId + "/access-url")
                        .header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaId").value(mediaId));
    }

    @Test
    void internalAccessUrlForUnknownMediaIsNotFound() throws Exception {
        mockMvc.perform(post("/internal/media/" + UUID.randomUUID() + "/access-url")
                        .header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
    }

    @Test
    void internalStatusReturnsReadyForScannedMedia() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);

        mockMvc.perform(get("/internal/media/" + mediaId + "/status")
                        .header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaId").value(mediaId))
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void internalStatusRequiresGatewayAuth() throws Exception {
        mockMvc.perform(get("/internal/media/" + UUID.randomUUID() + "/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GATEWAY_AUTH_REQUIRED"));
    }

    @Test
    void internalStatusForUnknownMediaIsNotFound() throws Exception {
        mockMvc.perform(get("/internal/media/" + UUID.randomUUID() + "/status")
                        .header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
    }

    private String upload(UUID owner) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "spot.jpg", "image/jpeg", JPEG);
        MvcResult result = mockMvc.perform(multipart("/api/v1/media/upload")
                        .file(file)
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("X-User-Id", owner)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.mediaId");
    }
}
