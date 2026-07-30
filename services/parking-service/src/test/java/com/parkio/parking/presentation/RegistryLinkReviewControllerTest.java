package com.parkio.parking.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.LinkReviewApplicationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RegistryLinkReviewControllerTest {
    private LinkReviewApplicationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(LinkReviewApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new RegistryLinkReviewController(service, new ObjectMapper()))
                .build();
    }

    @Test
    void missingIdentityIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/parking/admin/municipal/registry/link-candidates"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/parking/admin/municipal/registry/link-candidates")
                        .header("X-User-Roles", "USER"))
                .andExpect(status().isForbidden());
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
        UUID id = UUID.randomUUID();
        when(service.accept(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("Reviewed municipal registry linking is disabled"));
        mvc.perform(post("/api/v1/parking/admin/municipal/registry/link-candidates/{id}/accept", id)
                        .header("X-User-Roles", "SUPER_ADMIN")
                        .header("X-User-Id", "admin-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"chosenFacilityId":"00000000-0000-0000-0000-000000000001"}
                                """))
                .andExpect(status().isConflict());
    }
}
