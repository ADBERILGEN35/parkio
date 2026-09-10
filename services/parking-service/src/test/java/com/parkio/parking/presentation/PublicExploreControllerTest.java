package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.parking.application.PublicExploreQueryService;
import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.infrastructure.config.PublicExploreProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicExploreControllerTest {
    private PublicExploreQueryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PublicExploreQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PublicExploreController(service)).build();
    }

    @Test
    void listUsesPrivateCachePolicyAndReturnsDiscoveryEnvelope() throws Exception {
        when(service.discover(any())).thenReturn(new PublicExploreQueryService.DiscoveryResult(
                List.of(), 0L, 0L, null));
        mvc.perform(get("/api/v1/public/explore/facilities"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, max-age=30, stale-while-revalidate=120"))
                .andExpect(jsonPath("$.facilities").isArray())
                .andExpect(jsonPath("$.municipalTotalInScope").value(0))
                .andExpect(jsonPath("$.municipalHiddenCount").value(0))
                .andExpect(jsonPath("$.communitySpotCountInScope").value((Object) null));
    }

    @Test
    void rejectsClientLimitAboveServerCapAndUnknownParams() throws Exception {
        PublicExploreQueryService real = realService();
        mvc = MockMvcBuilders.standaloneSetup(new PublicExploreController(real)).build();
        mvc.perform(get("/api/v1/public/explore/facilities?limit=20"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/explore/facilities?page=1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/explore/facilities?offset=0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/explore/facilities?cursor=abc"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "lat=38.42",
            "lng=27.14",
            "lat=91&lng=27.14",
            "lat=38.42&lng=181",
            "lat=NaN&lng=27.14",
            "lat=38.42&lng=Infinity",
            "lat=38.42&lng=27.14&radiusMeters=0",
            "lat=38.42&lng=27.14&radiusMeters=-1",
            "lat=38.42&lng=27.14&radiusMeters=5001",
            "limit=0",
            "limit=-3",
            "limit=7",
            "limit=1.5",
            "radiusMeters=abc",
            "lat=not-a-number&lng=27.14"
    })
    void rejectsMalformedAndOutOfRangeParameterMatrix(String query) throws Exception {
        mvc = MockMvcBuilders.standaloneSetup(new PublicExploreController(realService())).build();
        mvc.perform(get("/api/v1/public/explore/facilities?" + query))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateQueryParameters() throws Exception {
        mvc.perform(get("/api/v1/public/explore/facilities?limit=6&limit=3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailPathIsNoLongerMappedOnPublicController() throws Exception {
        mvc.perform(get("/api/v1/public/explore/facilities/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void incidentalValidCredentialCannotPersonalizePublicOutput() throws Exception {
        var view = new PublicExploreQueryService.FacilityView(
                UUID.fromString("00000000-0000-0000-0000-000000000901"),
                "Konak Otopark", "IZELMAN A.S.", MunicipalFacilityType.OFF_STREET,
                "Konak, Izmir", 38.4237, 27.1428, 100, 42,
                MunicipalOccupancyFreshness.LIVE, Instant.parse("2026-09-04T10:00:00Z"),
                "Izmir Buyuksehir Belediyesi / IZUM", "CC BY 4.0 attribution");
        when(service.discover(any())).thenReturn(new PublicExploreQueryService.DiscoveryResult(
                List.of(view), 1L, 0L, null));

        String anonymous = mvc.perform(get("/api/v1/public/explore/facilities"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/public/explore/facilities")
                        .header("Authorization", "Bearer valid-normal-user-token"))
                .andExpect(status().isOk())
                .andExpect(content().json(anonymous, true));
        assertThat(anonymous).doesNotContain("hiddenFacilities");
        assertThat(anonymous).contains("\"municipalTotalInScope\":1");
    }

    @Test
    void controllerIsAbsentUnlessTheFlagIsExplicitlyTrue() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(PublicExploreQueryService.class, () -> mock(PublicExploreQueryService.class))
                .withUserConfiguration(ControllerConfiguration.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(PublicExploreController.class));
        runner.withPropertyValues("parkio.public-explore.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(PublicExploreController.class));
    }

    private static PublicExploreQueryService realService() {
        PublicExploreProperties props = new PublicExploreProperties();
        props.setEnabled(true);
        props.setAllowedSourceFamilies(List.of("izum"));
        return new PublicExploreQueryService(
                mock(MunicipalFacilityRepository.class),
                mock(MunicipalOccupancySnapshotRepository.class),
                mock(ParkingSpotRepository.class),
                props,
                Clock.systemUTC());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PublicExploreController.class)
    static class ControllerConfiguration {}
}
