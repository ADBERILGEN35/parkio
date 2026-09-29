package com.parkio.parking.presentation;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.LinkReviewApplicationService;
import com.parkio.parking.application.port.RegistryPersistencePort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RegistryLinkReviewControllerTest {
    private LinkReviewApplicationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(LinkReviewApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RegistryLinkReviewController(service, new ObjectMapper())).build();
    }

    @Test
    void missingIdentityIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/parking/admin/municipal/registry/link-candidates"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userRoleIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/parking/admin/municipal/registry/link-candidates")
                        .header("X-User-Roles", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminMayListBoundedCandidateDtosWithoutRawPayloadOrReviewNotes() throws Exception {
        RegistryPersistencePort.Candidate candidate = candidate();
        when(service.pending(0, 20, "PENDING"))
                .thenReturn(new RegistryPersistencePort.CandidatePage(List.of(candidate), 0, 20, 1));

        mvc.perform(get("/api/v1/parking/admin/municipal/registry/link-candidates")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(candidate.id().toString()))
                .andExpect(jsonPath("$.content[0].evidence.distanceMeters").value(12))
                .andExpect(jsonPath("$.content[0].rawSourcePayload").doesNotExist())
                .andExpect(jsonPath("$.content[0].rejectionReason").doesNotExist())
                .andExpect(jsonPath("$.content[0].reviewedBy").doesNotExist());
    }

    @Test
    void missingCandidateIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.detail(id)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/parking/admin/municipal/registry/link-candidates/{id}", id)
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void disabledReviewedLinkingIsConflict() throws Exception {
        when(service.accept(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("Reviewed municipal registry linking is disabled"));
        performAccept(UUID.randomUUID(), "{\"expectedVersion\":0,\"chosenFacilityId\":\"00000000-0000-0000-0000-000000000001\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void staleReviewIsConflict() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.accept(any(), anyLong(), any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(RegistryPersistencePort.Candidate.class, id));
        performAccept(id, "{\"expectedVersion\":0,\"chosenFacilityId\":\"00000000-0000-0000-0000-000000000001\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        performAccept(UUID.randomUUID(), "{not-json")
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedMethodIsMethodNotAllowed() throws Exception {
        mvc.perform(put("/api/v1/parking/admin/municipal/registry/link-candidates")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isMethodNotAllowed());
    }

    private org.springframework.test.web.servlet.ResultActions performAccept(UUID id, String body) throws Exception {
        return mvc.perform(post("/api/v1/parking/admin/municipal/registry/link-candidates/{id}/accept", id)
                .header("X-User-Roles", "SUPER_ADMIN")
                .header("X-User-Id", "admin-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static RegistryPersistencePort.Candidate candidate() {
        return new RegistryPersistencePort.Candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                UUID.fromString("00000000-0000-0000-0000-000000000012"),
                "izmir-izum-otoparklar", "izum-1", "osm-geofabrik-turkey", "osm-1", "IZUM_OSM",
                "{\"distanceMeters\":12}", "{\"distance\":0.15}", 0.75, "[]",
                Instant.parse("2026-07-30T12:00:00Z"), "v1", "v1", "PENDING",
                "sensitive-reviewer", null, "sensitive-rejection-note", null,
                "registry-link-candidate-v1", 0);
    }
}