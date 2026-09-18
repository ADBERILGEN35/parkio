package com.parkio.user.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.user.application.RecentDestinationApplicationService;
import com.parkio.user.application.RecentParkingApplicationService;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.RecentDestination;
import com.parkio.user.domain.place.RecentParking;
import com.parkio.user.domain.place.RecentParkingTargetKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecentControllerTest {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final UUID USER_ID = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000061");
    private static final UUID PROFILE = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000062");
    private static final UUID FACILITY = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000063");
    private static final Instant NOW = Instant.parse("2026-08-06T16:00:00Z");

    @Test
    void destinationsDisabledReturnsNotFound() throws Exception {
        RecentDestinationApplicationService service = mock(RecentDestinationApplicationService.class);
        destinationMvc(service, false)
                .perform(get("/api/v1/places/recents/destinations").header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECENTS_DISABLED"));
        verify(service, never()).list(any());
    }

    @Test
    void destinationsRequireAuth() throws Exception {
        RecentDestinationApplicationService service = mock(RecentDestinationApplicationService.class);
        destinationMvc(service, true)
                .perform(get("/api/v1/places/recents/destinations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void destinationsListConfirmDeleteClear() throws Exception {
        RecentDestinationApplicationService service = mock(RecentDestinationApplicationService.class);
        RecentDestination recent = RecentDestination.create(
                PROFILE, "Kordon", 38.43, 27.14, PlaceDestinationSource.MAP_PIN, null, null, NOW);
        when(service.list(USER_ID)).thenReturn(List.of(recent));
        when(service.confirm(eq(USER_ID), eq("Kordon"), eq(38.43), eq(27.14), any(), any(), any()))
                .thenReturn(recent);

        destinationMvc(service, true)
                .perform(get("/api/v1/places/recents/destinations").header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].label").value("Kordon"))
                .andExpect(jsonPath("$.items[0].useCount").value(1));

        destinationMvc(service, true)
                .perform(post("/api/v1/places/recents/destinations")
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Kordon","latitude":38.43,"longitude":27.14}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Kordon"));

        destinationMvc(service, true)
                .perform(delete("/api/v1/places/recents/destinations/" + recent.id())
                        .header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isNoContent());
        verify(service).delete(USER_ID, recent.id());

        destinationMvc(service, true)
                .perform(delete("/api/v1/places/recents/destinations").header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isNoContent());
        verify(service).clearAll(USER_ID);
    }

    @Test
    void parkingListRecordAndClear() throws Exception {
        RecentParkingApplicationService service = mock(RecentParkingApplicationService.class);
        RecentParking recent = RecentParking.create(
                PROFILE, RecentParkingTargetKind.MUNICIPAL_FACILITY, FACILITY, NOW);
        when(service.list(USER_ID)).thenReturn(List.of(recent));
        when(service.record(USER_ID, RecentParkingTargetKind.MUNICIPAL_FACILITY, FACILITY))
                .thenReturn(recent);

        parkingMvc(service, true)
                .perform(get("/api/v1/places/recents/parking").header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].targetId").value(FACILITY.toString()));

        parkingMvc(service, true)
                .perform(post("/api/v1/places/recents/parking")
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetId":"%s"}
                                """.formatted(FACILITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetKind").value("MUNICIPAL_FACILITY"));

        parkingMvc(service, true)
                .perform(delete("/api/v1/places/recents/parking").header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isNoContent());
        verify(service).clearAll(USER_ID);
    }

    private static MockMvc destinationMvc(RecentDestinationApplicationService service, boolean enabled) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return MockMvcBuilders.standaloneSetup(new RecentDestinationController(service, enabled))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .build();
    }

    private static MockMvc parkingMvc(RecentParkingApplicationService service, boolean enabled) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return MockMvcBuilders.standaloneSetup(new RecentParkingController(service, enabled))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .build();
    }
}
