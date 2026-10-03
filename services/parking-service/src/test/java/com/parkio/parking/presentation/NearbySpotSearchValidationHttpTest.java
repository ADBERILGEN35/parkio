package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CL-F38b: the authenticated nearby search validates coordinates like Public Explore
 * does. Non-finite or out-of-range values are a 400 before any spot query runs or a
 * search-log row (user id + coordinates) is written.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NearbySpotSearchValidationHttpTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearSearchLogs() {
        jdbc.update("DELETE FROM parking_spot_search_logs");
    }

    @ParameterizedTest(name = "lat={0} lng={1} radius={2}")
    @CsvSource({
            "NaN, 29.0, ''",
            "41.0, NaN, ''",
            "Infinity, 29.0, ''",
            "41.0, -Infinity, ''",
            "91, 29.0, ''",
            "-90.5, 29.0, ''",
            "41.0, 181, ''",
            "41.0, -180.5, ''",
            "41.0, 29.0, NaN",
            "41.0, 29.0, Infinity"})
    void invalidSearchIsRejectedBeforeAnyQueryOrLog(String lat, String lng, String radius) throws Exception {
        var request = get("/api/v1/parking/spots/nearby")
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("X-User-Id", UUID.randomUUID().toString())
                .param("lat", lat)
                .param("lng", lng);
        if (!radius.isEmpty()) {
            request.param("radius", radius);
        }

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parking_spot_search_logs", Integer.class))
                .isZero();
    }
}
