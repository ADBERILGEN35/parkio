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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.user.application.FavouriteDestinationApplicationService;
import com.parkio.user.application.FavouriteParkingApplicationService;
import com.parkio.user.domain.place.FavouriteDestination;
import com.parkio.user.domain.place.FavouriteParking;
import com.parkio.user.domain.place.FavouriteParkingTargetKind;
import com.parkio.user.domain.place.PlaceDestinationSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FavouriteControllerTest {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final UUID USER_ID = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000041");
    private static final UUID PROFILE = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000042");
    private static final UUID FACILITY = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000043");
    private static final UUID FAV_ID = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000044");
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    @Test
    void parkingDisabledReturnsNotFound() throws Exception {
        FavouriteParkingApplicationService service = mock(FavouriteParkingApplicationService.class);
        parkingMvc(service, false)
                .perform(get("/api/v1/places/favourites/parking").header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FAVOURITES_DISABLED"));
        verify(service, never()).list(any());
    }

    @Test
    void parkingListAndAdd() throws Exception {
        FavouriteParkingApplicationService service = mock(FavouriteParkingApplicationService.class);
        FavouriteParking fav = new FavouriteParking(
                FAV_ID, PROFILE, FavouriteParkingTargetKind.MUNICIPAL_FACILITY, FACILITY, NOW, 0L);
        when(service.list(USER_ID)).thenReturn(List.of(fav));
        when(service.addMunicipalFacility(USER_ID, FACILITY)).thenReturn(fav);

        parkingMvc(service, true)
                .perform(get("/api/v1/places/favourites/parking").header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].targetId").value(FACILITY.toString()));

        parkingMvc(service, true)
                .perform(post("/api/v1/places/favourites/parking")
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetId":"%s"}
                                """.formatted(FACILITY)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetKind").value("MUNICIPAL_FACILITY"));
    }

    @Test
    void parkingStatusAndDelete() throws Exception {
        FavouriteParkingApplicationService service = mock(FavouriteParkingApplicationService.class);
        when(service.statusFor(eq(USER_ID), any())).thenReturn(Set.of(FACILITY));

        parkingMvc(service, true)
                .perform(get("/api/v1/places/favourites/parking/status")
                        .header(USER_ID_HEADER, USER_ID)
                        .param("targetIds", FACILITY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favouritedTargetIds[0]").value(FACILITY.toString()));

        parkingMvc(service, true)
                .perform(delete("/api/v1/places/favourites/parking/" + FACILITY)
                        .header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isNoContent());
        verify(service).removeMunicipalFacility(USER_ID, FACILITY);
    }

    @Test
    void destinationMissingAuthUnauthorized() throws Exception {
        FavouriteDestinationApplicationService service = mock(FavouriteDestinationApplicationService.class);
        destinationMvc(service, true)
                .perform(get("/api/v1/places/favourites/destinations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void destinationCreateUpdateDelete() throws Exception {
        FavouriteDestinationApplicationService service = mock(FavouriteDestinationApplicationService.class);
        FavouriteDestination fav = FavouriteDestination.create(
                PROFILE, "Kordon", 38.43, 27.14, PlaceDestinationSource.MAP_PIN, null, null, NOW);
        when(service.add(eq(USER_ID), eq("Kordon"), eq(38.43), eq(27.14), any(), any(), any()))
                .thenReturn(fav);
        when(service.updateDisplay(eq(USER_ID), eq(fav.id()), eq("Kordon Alsancak"), any()))
                .thenReturn(fav);

        destinationMvc(service, true)
                .perform(post("/api/v1/places/favourites/destinations")
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Kordon","latitude":38.43,"longitude":27.14}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Kordon"));

        destinationMvc(service, true)
                .perform(put("/api/v1/places/favourites/destinations/" + fav.id())
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Kordon Alsancak"}
                                """))
                .andExpect(status().isOk());

        destinationMvc(service, true)
                .perform(delete("/api/v1/places/favourites/destinations/" + fav.id())
                        .header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isNoContent());
        verify(service).delete(USER_ID, fav.id());
    }

    private static MockMvc parkingMvc(FavouriteParkingApplicationService service, boolean enabled) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return MockMvcBuilders.standaloneSetup(new FavouriteParkingController(service, enabled))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .build();
    }

    private static MockMvc destinationMvc(FavouriteDestinationApplicationService service, boolean enabled) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return MockMvcBuilders.standaloneSetup(new FavouriteDestinationController(service, enabled))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .build();
    }
}
