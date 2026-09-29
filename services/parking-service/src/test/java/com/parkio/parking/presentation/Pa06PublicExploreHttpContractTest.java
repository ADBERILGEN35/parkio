package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.parkio.parking.application.PublicExploreQueryService;
import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.infrastructure.config.PublicExploreProperties;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * G06A: real controller + query-service HTTP/JSON contract for PA-06 withholding.
 * Community repository is not a constructor dependency; municipal fixtures stay fixed
 * while overlapping geometry requests must not produce a count-related signal.
 */
class Pa06PublicExploreHttpContractTest {
    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
    private static final UUID FACILITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final Set<String> IZUM_KEYS = Set.of(MunicipalSourceIdentity.IZUM);

    private MunicipalFacilityRepository facilities;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        facilities = mock(MunicipalFacilityRepository.class);
        when(facilities.countPublicExploreNearby(anyDouble(), anyDouble(), anyInt(), any()))
                .thenReturn(1L);
        when(facilities.publicExploreNearby(anyDouble(), anyDouble(), anyInt(), anyInt(), any()))
                .thenReturn(List.of(facility()));

        PublicExploreProperties props = new PublicExploreProperties();
        props.setEnabled(true);
        props.setAllowedSourceFamilies(List.of("izum"));

        PublicExploreQueryService service = new PublicExploreQueryService(
                facilities,
                mock(MunicipalOccupancySnapshotRepository.class),
                mock(com.parkio.parking.application.port.RoadsideDiscoveryQueryPort.class),
                props,
                Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new PublicExploreController(service)).build();
    }

    @Test
    void serializedJsonKeepsCommunityFieldAsNullNotZeroOrAbsent() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/public/explore/facilities")
                        .param("lat", "38.4237")
                        .param("lng", "27.1428")
                        .param("radiusMeters", "5000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, max-age=30, stale-while-revalidate=120"))
                .andExpect(jsonPath("$.communitySpotCountInScope").value((Object) null))
                .andExpect(jsonPath("$.municipalTotalInScope").value(1))
                .andExpect(jsonPath("$.municipalHiddenCount").value(0))
                .andExpect(jsonPath("$.facilities.length()").value(1))
                .andExpect(jsonPath("$.facilities[0].id").value(FACILITY_ID.toString()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"communitySpotCountInScope\":null");
        assertThat(body).doesNotContain("\"communitySpotCountInScope\":0");
        assertThat(JsonPath.<Object>read(body, "$.communitySpotCountInScope")).isNull();
    }

    @Test
    void overlappingGeometryWithFixedMunicipalYieldsIdenticalPrivacyEnvelope() throws Exception {
        String[] queries = {
            "lat=38.4237&lng=27.1428&radiusMeters=99",
            "lat=38.4237&lng=27.1428&radiusMeters=101",
            "lat=38.42371&lng=27.1428&radiusMeters=100",
            "lat=38.42369&lng=27.1428&radiusMeters=100",
            "lat=38.4237&lng=27.1428&radiusMeters=5000&limit=6"
        };

        String baseline = null;
        for (String query : queries) {
            String body = mvc.perform(get("/api/v1/public/explore/facilities?" + query))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.communitySpotCountInScope").value((Object) null))
                    .andExpect(jsonPath("$.municipalTotalInScope").value(1))
                    .andExpect(jsonPath("$.municipalHiddenCount").value(0))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            if (baseline == null) {
                baseline = privacyEnvelope(body);
            } else {
                assertThat(privacyEnvelope(body)).isEqualTo(baseline);
            }
        }
        verify(facilities, times(queries.length))
                .countPublicExploreNearby(anyDouble(), anyDouble(), anyInt(), any());
    }

    @Test
    void emptyMunicipalStillWithholdsCommunityAsNullNotZero() throws Exception {
        when(facilities.countPublicExploreNearby(anyDouble(), anyDouble(), anyInt(), any()))
                .thenReturn(0L);
        when(facilities.publicExploreNearby(anyDouble(), anyDouble(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        String body = mvc.perform(get("/api/v1/public/explore/facilities")
                        .param("lat", "38.4237")
                        .param("lng", "27.1428"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facilities").isEmpty())
                .andExpect(jsonPath("$.municipalTotalInScope").value(0))
                .andExpect(jsonPath("$.communitySpotCountInScope").value((Object) null))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("\"communitySpotCountInScope\":null");
        assertThat(body).doesNotContain("\"communitySpotCountInScope\":0");
    }

    @Test
    void queryServiceConstructorHasNoCommunitySpotRepositoryDependency() {
        Constructor<?> ctor = Stream.of(PublicExploreQueryService.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == 5)
                .findFirst()
                .orElseThrow();
        assertThat(ctor.getParameterTypes())
                .containsExactly(
                        MunicipalFacilityRepository.class,
                        MunicipalOccupancySnapshotRepository.class,
                        com.parkio.parking.application.port.RoadsideDiscoveryQueryPort.class,
                        PublicExploreProperties.class,
                        Clock.class);
        assertThat(ctor.getParameterTypes())
                .noneMatch(type -> type.getSimpleName().contains("ParkingSpot"));
    }

    private static String privacyEnvelope(String body) {
        return "community=" + JsonPath.read(body, "$.communitySpotCountInScope")
                + "|municipalTotal=" + JsonPath.read(body, "$.municipalTotalInScope")
                + "|municipalHidden=" + JsonPath.read(body, "$.municipalHiddenCount")
                + "|facilityCount=" + ((List<?>) JsonPath.read(body, "$.facilities")).size()
                + "|facilityId=" + JsonPath.read(body, "$.facilities[0].id");
    }

    private static MunicipalFacilityRepository.Facility facility() {
        return new MunicipalFacilityRepository.Facility(
                FACILITY_ID,
                "Konak Otopark",
                "IZELMAN A.S.",
                MunicipalFacilityType.OFF_STREET,
                "Konak, Izmir",
                38.4237,
                27.1428,
                120,
                true,
                true,
                "ignored",
                "ignored",
                60,
                120,
                MunicipalSourceIdentity.IZUM,
                IZUM_KEYS,
                MunicipalAccessClassification.PUBLIC);
    }
}
