package com.parkio.parking.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.recommendation.InventoryChannelStatus;
import com.parkio.parking.application.recommendation.ParkingCandidate;
import com.parkio.parking.application.recommendation.ParkingCandidateChannel;
import com.parkio.parking.application.recommendation.RecommendationApplicationService;
import com.parkio.parking.application.recommendation.RecommendationReason;
import com.parkio.parking.application.recommendation.RecommendationReasonCode;
import com.parkio.parking.application.recommendation.RecommendationResult;
import com.parkio.parking.application.recommendation.ranking.RankingStatus;
import com.parkio.parking.application.recommendation.ranking.RankingVersion;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.domain.place.Destination;
import com.parkio.parking.domain.place.DestinationSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecommendationControllerTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    private RecommendationApplicationService service;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        service = Mockito.mock(RecommendationApplicationService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RecommendationController(service, true))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .setMessageConverters(converter)
                .build();
    }

    @Test
    void flagOffReturnsNotFound() throws Exception {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);
        MockMvc disabled = MockMvcBuilders
                .standaloneSetup(new RecommendationController(service, false))
                .setControllerAdvice(new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC)))
                .setMessageConverters(converter)
                .build();

        disabled.perform(post("/api/v1/parking/recommendations")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECOMMENDATIONS_DISABLED"));
    }

    @Test
    void missingUserReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/parking/recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));
    }

    @Test
    void validRequestReturnsCandidates() throws Exception {
        Destination destination = Destination.of("Forum Bornova", 38.45, 27.2, DestinationSource.GEOCODING);
        ParkingCandidate candidate = new ParkingCandidate(
                "municipal:" + UUID.randomUUID(),
                ParkingCandidateChannel.MUNICIPAL_FACILITY,
                UUID.randomUUID().toString(),
                "Katlı Otopark",
                38.4505,
                27.2005,
                80,
                null,
                "IZUM",
                0,
                List.of(RecommendationReason.of(RecommendationReasonCode.CLOSE_TO_DESTINATION)));
        when(service.recommend(any())).thenReturn(new RecommendationResult(
                destination,
                NOW,
                false,
                InventoryChannelStatus.EMPTY,
                InventoryChannelStatus.AVAILABLE,
                List.of(candidate),
                List.of(),
                RankingVersion.DISTANCE_BASELINE_V1,
                RankingStatus.DISABLED));

        mockMvc.perform(post("/api/v1/parking/recommendations")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.rankingVersion").value("DISTANCE_BASELINE_V1"))
                .andExpect(jsonPath("$.rankingStatus").value("DISABLED"))
                .andExpect(jsonPath("$.destination.label").value("Forum Bornova"))
                .andExpect(jsonPath("$.inventoryStatus.municipal").value("AVAILABLE"))
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].channel").value("MUNICIPAL_FACILITY"))
                .andExpect(jsonPath("$.candidates[0].baselineOrder").value(0));
    }

    @Test
    void invalidDestinationCoordinatesReturnBadRequest() throws Exception {
        String body = """
                {
                  "destination": {
                    "label": "Bad",
                    "latitude": 999,
                    "longitude": 27.1,
                    "source": "MAP_PIN"
                  }
                }
                """;
        mockMvc.perform(post("/api/v1/parking/recommendations")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DESTINATION"));
    }

    @Test
    void oversizedRadiusRejectedByService() throws Exception {
        when(service.recommend(any())).thenAnswer(invocation -> {
            throw new ParkingException(
                    ParkingErrorCode.INVALID_RECOMMENDATION_RADIUS,
                    "radiusMeters must be between 1 and 5000");
        });

        String body = """
                {
                  "destination": {
                    "label": "Forum Bornova",
                    "latitude": 38.45,
                    "longitude": 27.2,
                    "source": "GEOCODING"
                  },
                  "radiusMeters": 9000
                }
                """;
        mockMvc.perform(post("/api/v1/parking/recommendations")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECOMMENDATION_RADIUS"));
    }

    private String validBody() throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "destination",
                java.util.Map.of(
                        "label", "Forum Bornova",
                        "latitude", 38.45,
                        "longitude", 27.2,
                        "source", "GEOCODING"),
                "radiusMeters",
                1500,
                "limit",
                10));
    }
}
