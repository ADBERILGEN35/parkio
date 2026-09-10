package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.parking.application.IzelmanImportApplicationService;
import com.parkio.parking.application.IzelmanImportResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.externalsource.izelman.SourceAgeClassification;
import com.parkio.parking.infrastructure.metrics.IzelmanImportMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IzelmanImportControllerTest {
    private IzelmanImportApplicationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(IzelmanImportApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new IzelmanImportController(service, new IzelmanImportMetrics(new SimpleMeterRegistry())))
                .build();
    }

    @Test
    void rejectsNonAdmin() throws Exception {
        mvc.perform(post("/api/v1/parking/municipal/sources/"
                        + IzelmanSourceKeys.OPEN + "/izelman-import")
                        .header("X-User-Roles", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownSourceIsNotFound() throws Exception {
        mvc.perform(post("/api/v1/parking/municipal/sources/unknown/izelman-import")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void dryRunReturnsSummary() throws Exception {
        when(service.importConfigured(eq(IzelmanSourceKeys.OPEN), anyBoolean()))
                .thenReturn(new IzelmanImportResult(
                        MunicipalSyncRunStatus.SUCCESS, IzelmanSourceKeys.OPEN, "FACILITY", true,
                        2, 2, 0, 0, 0, 0, 0, SourceAgeClassification.HISTORICAL, null));
        String body = mvc.perform(post("/api/v1/parking/municipal/sources/"
                        + IzelmanSourceKeys.OPEN + "/izelman-import?dryRun=true")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("HISTORICAL").doesNotContain("OTOPARK_ADI");
    }
}
