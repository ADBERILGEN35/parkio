package com.parkio.user.presentation;

import com.parkio.user.application.SavedPlaceApplicationService;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.PlaceIdentity;
import com.parkio.user.domain.place.SavedPlace;
import com.parkio.user.presentation.dto.CreateCustomSavedPlaceRequest;
import com.parkio.user.presentation.dto.SavedPlaceListResponse;
import com.parkio.user.presentation.dto.SavedPlaceResponse;
import com.parkio.user.presentation.dto.UpsertSavedPlaceRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated Saved Places API (WP-SPA-03).
 * Gated by {@code parkio.spa.saved-places.enabled}.
 */
@RestController
@RequestMapping("/api/v1/places/saved")
public class SavedPlaceController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final SavedPlaceApplicationService savedPlaces;
    private final boolean savedPlacesEnabled;

    public SavedPlaceController(
            SavedPlaceApplicationService savedPlaces,
            @Value("${parkio.spa.saved-places.enabled:false}") boolean savedPlacesEnabled) {
        this.savedPlaces = savedPlaces;
        this.savedPlacesEnabled = savedPlacesEnabled;
    }

    @GetMapping
    public SavedPlaceListResponse list(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        requireEnabled();
        return new SavedPlaceListResponse(
                savedPlaces.list(requireUserId(userId)).stream().map(SavedPlaceResponse::from).toList());
    }

    @PutMapping("/home")
    public SavedPlaceResponse upsertHome(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody UpsertSavedPlaceRequest request) {
        requireEnabled();
        return SavedPlaceResponse.from(savedPlaces.upsertHome(
                requireUserId(userId),
                request.latitude(),
                request.longitude(),
                request.label(),
                request.source(),
                toIdentity(request.placeIdentity()),
                request.subtitle()));
    }

    @PutMapping("/work")
    public SavedPlaceResponse upsertWork(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody UpsertSavedPlaceRequest request) {
        requireEnabled();
        return SavedPlaceResponse.from(savedPlaces.upsertWork(
                requireUserId(userId),
                request.latitude(),
                request.longitude(),
                request.label(),
                request.source(),
                toIdentity(request.placeIdentity()),
                request.subtitle()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedPlaceResponse createCustom(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody CreateCustomSavedPlaceRequest request) {
        requireEnabled();
        return SavedPlaceResponse.from(savedPlaces.createCustom(
                requireUserId(userId),
                request.label(),
                request.latitude(),
                request.longitude(),
                request.source() == null ? PlaceDestinationSource.MAP_PIN : request.source(),
                toIdentity(request.placeIdentity()),
                request.subtitle()));
    }

    @PutMapping("/{id}")
    public SavedPlaceResponse updateCustom(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody CreateCustomSavedPlaceRequest request) {
        requireEnabled();
        return SavedPlaceResponse.from(savedPlaces.updateCustom(
                requireUserId(userId),
                id,
                request.label(),
                request.latitude(),
                request.longitude(),
                request.source(),
                toIdentity(request.placeIdentity()),
                request.subtitle()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable("id") UUID id) {
        requireEnabled();
        savedPlaces.delete(requireUserId(userId), id);
    }

    @DeleteMapping("/home")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearHome(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        requireEnabled();
        savedPlaces.clearHome(requireUserId(userId));
    }

    private void requireEnabled() {
        if (!savedPlacesEnabled) {
            throw new UserException(UserErrorCode.SAVED_PLACES_DISABLED);
        }
    }

    private static UUID requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new UserException(UserErrorCode.MISSING_USER_ID);
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new UserException(UserErrorCode.MISSING_USER_ID);
        }
    }

    private static PlaceIdentity toIdentity(UpsertSavedPlaceRequest.PlaceIdentityRequest request) {
        if (request == null) {
            return null;
        }
        return PlaceIdentity.of(request.provider(), request.providerPlaceId());
    }
}
