package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.parking.application.PublicExploreQueryService;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    void listHasExactCachePolicyAndRejectsCallerControlledQuery() throws Exception {
        when(service.list()).thenReturn(List.of());
        mvc.perform(get("/api/v1/public/explore/facilities"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=30, stale-while-revalidate=120"));
        mvc.perform(get("/api/v1/public/explore/facilities?limit=999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void excludedDetailIsIndistinguishableFromMissingDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.findById(id)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/public/explore/facilities/{id}", id))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/explore/facilities/{id}?radius=50000", id))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidDetailIdentifierUsesTheSafeMvcBadRequestConvention() throws Exception {
        mvc.perform(get("/api/v1/public/explore/facilities/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void incidentalValidCredentialCannotPersonalizePublicOutput() throws Exception {
        var view = new PublicExploreQueryService.FacilityView(
                UUID.fromString("00000000-0000-0000-0000-000000000901"),
                "Konak Otopark", "IZELMAN A.S.", MunicipalFacilityType.OFF_STREET,
                "Konak, Izmir", 38.4237, 27.1428, 100, 42,
                MunicipalOccupancyFreshness.LIVE, Instant.parse("2026-09-04T10:00:00Z"),
                "Izmir Buyuksehir Belediyesi / IZUM", "CC BY 4.0 attribution");
        when(service.list()).thenReturn(List.of(view));

        String anonymous = mvc.perform(get("/api/v1/public/explore/facilities"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/public/explore/facilities")
                        .header("Authorization", "Bearer valid-normal-user-token"))
                .andExpect(status().isOk())
                .andExpect(content().json(anonymous, true));
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

    @Configuration(proxyBeanMethods = false)
    @Import(PublicExploreController.class)
    static class ControllerConfiguration {}
}
