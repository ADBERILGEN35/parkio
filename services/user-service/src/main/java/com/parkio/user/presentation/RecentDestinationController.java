package com.parkio.user.presentation;

import com.parkio.user.application.RecentDestinationApplicationService;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.PlaceIdentity;
import com.parkio.user.presentation.dto.ConfirmRecentDestinationRequest;
import com.parkio.user.presentation.dto.RecentDestinationListResponse;
import com.parkio.user.presentation.dto.RecentDestinationResponse;
import com.parkio.user.presentation.dto.UpsertSavedPlaceRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated recent destinations API (WP-SPA-07).
 * Gated by {@code parkio.spa.recents.enabled}.
 */
@RestController
@RequestMapping("/api/v1/places/recents/destinations")
public class RecentDestinationController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final RecentDestinationApplicationService recents;
    private final boolean recentsEnabled;

    public RecentDestinationController(
            RecentDestinationApplicationService recents,
            @Value("${parkio.spa.recents.enabled:false}") boolean recentsEnabled) {
        this.recents = recents;
        this.recentsEnabled = recentsEnabled;
    }

    @GetMapping
    public RecentDestinationListResponse list(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        requireEnabled();
        return new RecentDestinationListResponse(
                recents.list(requireUserId(userId)).stream()
                        .map(RecentDestinationResponse::from)
                        .toList());
    }

    @PostMapping
    public RecentDestinationResponse confirm(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody ConfirmRecentDestinationRequest request) {
        requireEnabled();
        return RecentDestinationResponse.from(recents.confirm(
                requireUserId(userId),
                request.label(),
                request.latitude(),
                request.longitude(),
                request.source() == null ? PlaceDestinationSource.MAP_PIN : request.source(),
                toIdentity(request.placeIdentity()),
                request.subtitle()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable("id") UUID id) {
        requireEnabled();
        recents.delete(requireUserId(userId), id);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearAll(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        requireEnabled();
        recents.clearAll(requireUserId(userId));
    }

    private void requireEnabled() {
        if (!recentsEnabled) {
            throw new UserException(UserErrorCode.RECENTS_DISABLED);
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
