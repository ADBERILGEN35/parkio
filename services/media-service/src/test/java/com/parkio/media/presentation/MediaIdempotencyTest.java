package com.parkio.media.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.testsupport.TestImages;
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

@SpringBootTest
@AutoConfigureMockMvc
class MediaIdempotencyTest {

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
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        mockMvc.perform(uploadRequest(UUID.randomUUID(), null, "spot.jpg", JPEG))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        assertThat(count("media_files")).isZero();
        verify(storage, times(0)).store(anyString(), any(byte[].class), anyString());
    }

    @Test
    void retryReturnsSameMediaWithoutDuplicateRowsOrStorageWrite() throws Exception {
        UUID userId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(uploadRequest(userId, key, "spot.jpg", JPEG))
                .andExpect(status().isCreated())
                .andReturn();
        String mediaId = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.mediaId");

        mockMvc.perform(uploadRequest(userId, key, "spot.jpg", JPEG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaId").value(mediaId));

        assertThat(count("media_files")).isEqualTo(1);
        // FILE_SIZE, MIME_TYPE, DUPLICATE, MALWARE_SCAN
        assertThat(count("media_validation_results")).isEqualTo(4);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
        verify(storage, times(1)).store(anyString(), any(byte[].class), anyString());
    }

    @Test
    void sameKeyWithDifferentUploadReturnsConflict() throws Exception {
        UUID userId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        mockMvc.perform(uploadRequest(userId, key, "spot.jpg", JPEG))
                .andExpect(status().isCreated());

        byte[] different = TestImages.jpeg(16, 16, 97);
        mockMvc.perform(uploadRequest(userId, key, "other.jpg", different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        assertThat(count("media_files")).isEqualTo(1);
        verify(storage, times(1)).store(anyString(), any(byte[].class), anyString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder uploadRequest(
            UUID userId, String key, String filename, byte[] content) {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename, "image/jpeg", content);
        var request = multipart("/api/v1/media/upload")
                .file(file)
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("X-User-Id", userId);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return request;
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
