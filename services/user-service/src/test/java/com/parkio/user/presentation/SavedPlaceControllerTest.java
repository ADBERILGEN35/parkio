package com.parkio.user.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

import com.parkio.user.application.SavedPlaceApplicationService;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.SavedPlace;
import com.parkio.user.domain.place.SavedPlaceKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SavedPlaceControllerTest {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final UUID USER_ID = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000031");
    private static final UUID PROFILE = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000032");
    private static final UUID PLACE_ID = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000033");
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    @Test
    void disabledFlagReturnsNotFound() throws Exception {
        SavedPlaceApplicationService savedPlaces = mock(SavedPlaceApplicationService.class);
        mvc(savedPlaces, false)
                .perform(get("/api/v1/places/saved").header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SAVED_PLACES_DISABLED"));
        verify(savedPlaces, never()).list(any());
    }

    @Test
    void missingUserIdIsUnauthorized() throws Exception {
        SavedPlaceApplicationService savedPlaces = mock(SavedPlaceApplicationService.class);
        mvc(savedPlaces, true)
                .perform(get("/api/v1/places/saved"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));
    }

    @Test
    void listReturnsItems() throws Exception {
        SavedPlaceApplicationService savedPlaces = mock(SavedPlaceApplicationService.class);
        when(savedPlaces.list(USER_ID)).thenReturn(List.of(sampleHome()));
        mvc(savedPlaces, true)
                .perform(get("/api/v1/places/saved").header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].kind").value("HOME"))
                .andExpect(jsonPath("$.items[0].label").value("Home"))
                .andExpect(jsonPath("$.items[0].latitude").value(41.01));
    }

    @Test
    void putHomeDelegates() throws Exception {
        SavedPlaceApplicationService savedPlaces = mock(SavedPlaceApplicationService.class);
        when(savedPlaces.upsertHome(eq(USER_ID), anyDouble(), anyDouble(), any(), any(), any(), any()))
                .thenReturn(sampleHome());
        mvc(savedPlaces, true)
                .perform(put("/api/v1/places/saved/home")
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude":41.01,"longitude":28.97,"source":"MAP_PIN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("HOME"));
        verify(savedPlaces).upsertHome(eq(USER_ID), eq(41.01), eq(28.97), isNull(),
                eq(PlaceDestinationSource.MAP_PIN), isNull(), isNull());
    }

    @Test
    void postCustomCreates() throws Exception {
        SavedPlaceApplicationService savedPlaces = mock(SavedPlaceApplicationService.class);
        when(savedPlaces.createCustom(eq(USER_ID), anyString(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(sampleCustom());
        mvc(savedPlaces, true)
                .perform(post("/api/v1/places/saved")
                        .header(USER_ID_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Market","latitude":41.2,"longitude":29.2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("CUSTOM"))
                .andExpect(jsonPath("$.label").value("Market"));
    }

    @Test
    void deleteHomeClears() throws Exception {
        SavedPlaceApplicationService savedPlaces = mock(SavedPlaceApplicationService.class);
        mvc(savedPlaces, true)
                .perform(delete("/api/v1/places/saved/home").header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isNoContent());
        verify(savedPlaces).clearHome(USER_ID);
    }

    private static MockMvc mvc(SavedPlaceApplicationService savedPlaces, boolean enabled) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return MockMvcBuilders.standaloneSetup(new SavedPlaceController(savedPlaces, enabled))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .build();
    }

    private static SavedPlace sampleHome() {
        return new SavedPlace(
                PLACE_ID, PROFILE, SavedPlaceKind.HOME, null, 41.01, 28.97,
                PlaceDestinationSource.SYSTEM, null, null, NOW, NOW, 0L);
    }

    private static SavedPlace sampleCustom() {
        return new SavedPlace(
                PLACE_ID, PROFILE, SavedPlaceKind.CUSTOM, "Market", 41.2, 29.2,
                PlaceDestinationSource.MAP_PIN, null, null, NOW, NOW, 0L);
    }
}
