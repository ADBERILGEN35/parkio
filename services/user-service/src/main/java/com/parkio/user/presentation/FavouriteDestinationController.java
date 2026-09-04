package com.parkio.user.presentation;

import com.parkio.user.application.FavouriteDestinationApplicationService;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.PlaceIdentity;
import com.parkio.user.presentation.dto.CreateFavouriteDestinationRequest;
import com.parkio.user.presentation.dto.FavouriteDestinationListResponse;
import com.parkio.user.presentation.dto.FavouriteDestinationResponse;
import com.parkio.user.presentation.dto.UpdateFavouriteDestinationRequest;
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
 * Authenticated destination favourites API (WP-SPA-04).
 * Gated by {@code parkio.spa.favourites.enabled}.
 */
@RestController
@RequestMapping("/api/v1/places/favourites/destinations")
public class FavouriteDestinationController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final FavouriteDestinationApplicationService favourites;
    private final boolean favouritesEnabled;

    public FavouriteDestinationController(
            FavouriteDestinationApplicationService favourites,
            @Value("${parkio.spa.favourites.enabled:false}") boolean favouritesEnabled) {
        this.favourites = favourites;
        this.favouritesEnabled = favouritesEnabled;
    }

    @GetMapping
    public FavouriteDestinationListResponse list(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        requireEnabled();
        return new FavouriteDestinationListResponse(
                favourites.list(requireUserId(userId)).stream()
                        .map(FavouriteDestinationResponse::from)
                        .toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FavouriteDestinationResponse add(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody CreateFavouriteDestinationRequest request) {
        requireEnabled();
        return FavouriteDestinationResponse.from(favourites.add(
                requireUserId(userId),
                request.label(),
                request.latitude(),
                request.longitude(),
                request.source() == null ? PlaceDestinationSource.MAP_PIN : request.source(),
                toIdentity(request.placeIdentity()),
                request.subtitle()));
    }

    @PutMapping("/{id}")
    public FavouriteDestinationResponse update(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateFavouriteDestinationRequest request) {
        requireEnabled();
        return FavouriteDestinationResponse.from(
                favourites.updateDisplay(requireUserId(userId), id, request.label(), request.subtitle()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable("id") UUID id) {
        requireEnabled();
        favourites.delete(requireUserId(userId), id);
    }

    private void requireEnabled() {
        if (!favouritesEnabled) {
            throw new UserException(UserErrorCode.FAVOURITES_DISABLED);
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
