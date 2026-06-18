package com.parkio.media.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.media.application.port.MediaScanner;
import com.parkio.media.application.port.MediaScannerUnavailableException;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.testsupport.TestImages;
import io.micrometer.core.instrument.MeterRegistry;
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

/**
 * HTTP-contract + metrics tests for the malware-scan upload path. The scanner and
 * storage are mocked so each verdict (clean / infected / unavailable) can be driven
 * deterministically: a clean upload becomes {@code READY}; an infected file is
 * rejected with 422; a scanner that cannot complete fails closed with 503. The
 * matching scan counters are asserted to increment.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MediaScanUploadTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";
    private static final byte[] JPEG = TestImages.jpeg();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockBean
    private MediaStoragePort storage;

    @MockBean
    private MediaScanner scanner;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM media_validation_results");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM media_files");
        reset(storage, scanner);
        when(storage.store(anyString(), any(byte[].class), anyString()))
                .thenAnswer(invocation -> new MediaStoragePort.StoredObject(
                        "test-bucket", invocation.getArgument(0)));
    }

    @Test
    void cleanUploadBecomesReadyAndCountsCleanScan() throws Exception {
        when(scanner.scan(any(byte[].class))).thenReturn(MediaScanner.ScanReport.ofClean());
        double before = counter("media_scan_clean_total");

        mockMvc.perform(upload())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"));

        assertThat(counter("media_scan_clean_total")).isEqualTo(before + 1);
    }

    @Test
    void infectedUploadIsRejectedWith422AndCountsRejectedScan() throws Exception {
        when(scanner.scan(any(byte[].class)))
                .thenReturn(MediaScanner.ScanReport.ofInfected("Eicar-Test-Signature"));
        double before = counter("media_scan_rejected_total");

        mockMvc.perform(upload())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MEDIA_INFECTED"));

        assertThat(counter("media_scan_rejected_total")).isEqualTo(before + 1);
    }

    @Test
    void scannerUnavailableFailsClosedWith503AndCountsFailedScan() throws Exception {
        when(scanner.scan(any(byte[].class)))
                .thenThrow(new MediaScannerUnavailableException("clamd down (test)"));
        double before = counter("media_scan_failed_total");

        mockMvc.perform(upload())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEDIA_SCAN_UNAVAILABLE"));

        assertThat(counter("media_scan_failed_total")).isEqualTo(before + 1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder upload() {
        MockMultipartFile file = new MockMultipartFile("file", "spot.jpg", "image/jpeg", JPEG);
        return multipart("/api/v1/media/upload")
                .file(file)
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private double counter(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
