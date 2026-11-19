package com.parkio.media.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.testsupport.TestImages;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * HTTP-contract tests for media access control (IDOR prevention) and the signed
 * access-URL endpoint: identity is required, reads are owner-or-moderator only,
 * owner writes (claimed region, delete) are owner-only, every unauthorized caller
 * is answered as 404 (no id probing), and responses never expose storage internals
 * (bucket name, object key, checksum).
 */
@SpringBootTest
@AutoConfigureMockMvc
class MediaAccessControlTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";
    private static final byte[] JPEG = TestImages.jpeg();

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
    void ownerCanReadMetadataWithoutStorageInternals() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);

        MvcResult result = mockMvc.perform(authedGet("/api/v1/media/" + mediaId, owner, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaId").value(mediaId))
                .andExpect(jsonPath("$.ownerUserId").value(owner.toString()))
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andReturn();

        // Storage internals must never appear in the response body.
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("bucket", "objectKey", "object_key", "checksum", "accessUrl");
    }

    @Test
    void metadataRequiresUserId() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);

        mockMvc.perform(get("/api/v1/media/" + mediaId).header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));
    }

    @Test
    void nonOwnerMetadataReadIsNotFound() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);

        mockMvc.perform(authedGet("/api/v1/media/" + mediaId, UUID.randomUUID(), null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
    }

    @Test
    void normalNonOwnerCannotReadValidationResults() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);

        mockMvc.perform(authedGet("/api/v1/media/" + mediaId + "/validation-results", UUID.randomUUID(), "USER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void moderatorCanReadValidationResults() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);

        mockMvc.perform(authedGet("/api/v1/media/" + mediaId + "/validation-results", UUID.randomUUID(), "MODERATOR"))
                .andExpect(status().isOk())
                // FILE_SIZE, MIME_TYPE, DUPLICATE, MALWARE_SCAN
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void adminCanReadAnotherUsersMetadata() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);

        mockMvc.perform(authedGet("/api/v1/media/" + mediaId, UUID.randomUUID(), "ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void ownerObtainsSignedUrlWithoutStorageInternalsInResponse() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);

        MvcResult result = mockMvc.perform(authedGet("/api/v1/media/" + mediaId + "/access-url", owner, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaId").value(mediaId))
                .andExpect(jsonPath("$.accessUrl").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        // Only mediaId/accessUrl/expiresAt: no bucket or object-key fields.
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("bucket", "objectKey", "object_key", "checksum");
    }

    @Test
    void nonOwnerCannotObtainSignedUrl() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);

        mockMvc.perform(authedGet("/api/v1/media/" + mediaId + "/access-url", UUID.randomUUID(), null))
                .andExpect(status().isNotFound());
    }

    @Test
    void accessUrlIsNotPersisted() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);
        mockMvc.perform(authedGet("/api/v1/media/" + mediaId + "/access-url", owner, null))
                .andExpect(status().isOk());

        // The media_files schema (generated from the entity) carries no access_url
        // column at all; V8 drops it on PostgreSQL.
        Integer accessUrlColumns = jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE LOWER(table_name) = 'media_files' AND LOWER(column_name) = 'access_url'
                """,
                Integer.class);
        assertThat(accessUrlColumns).isZero();
    }

    @Test
    void deleteIsOwnerOnly() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);
        UUID otherUser = UUID.randomUUID();

        // A non-owner gets the answer of an unknown id: 404 MEDIA_NOT_FOUND, never a 403 that
        // would confirm the media exists.
        assertThat(notFoundMessage(authedDelete("/api/v1/media/" + mediaId, otherUser)))
                .isEqualTo(notFoundMessage(authedDelete("/api/v1/media/" + UUID.randomUUID(), otherUser)));
        verify(storage, never()).delete(anyString());

        mockMvc.perform(authedDelete("/api/v1/media/" + mediaId, owner))
                .andExpect(status().isNoContent());
        verify(storage).delete(startsWith("media/" + owner + "/"));
    }

    @Test
    void claimedRegionUpdateIsOwnerOnly() throws Exception {
        UUID owner = UUID.randomUUID();
        String mediaId = upload(owner);
        UUID otherUser = UUID.randomUUID();

        assertThat(notFoundMessage(claimedRegionUpdate(mediaId, otherUser)))
                .isEqualTo(notFoundMessage(claimedRegionUpdate(UUID.randomUUID().toString(), otherUser)));

        mockMvc.perform(claimedRegionUpdate(mediaId, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimedRegion.width").value(0.5));
    }

    /** Performs the request, expects 404 MEDIA_NOT_FOUND and returns the error message. */
    private String notFoundMessage(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.message");
    }

    private MockHttpServletRequestBuilder authedDelete(String path, UUID userId) {
        return delete(path)
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("X-User-Id", userId);
    }

    private MockHttpServletRequestBuilder claimedRegionUpdate(String mediaId, UUID userId) {
        return patch("/api/v1/media/" + mediaId + "/claimed-region")
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("X-User-Id", userId)
                .contentType("application/json")
                .content("{\"x\":0.1,\"y\":0.1,\"width\":0.5,\"height\":0.5}");
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

    private MockHttpServletRequestBuilder authedGet(String path, UUID userId, String roles) {
        MockHttpServletRequestBuilder request = get(path)
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("X-User-Id", userId);
        if (roles != null) {
            request.header("X-User-Roles", roles);
        }
        return request;
    }
}
