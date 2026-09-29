package com.parkio.user.presentation;

import com.parkio.user.application.FavouriteParkingApplicationService;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.FavouriteParkingTargetKind;
import com.parkio.user.presentation.dto.CreateFavouriteParkingRequest;
import com.parkio.user.presentation.dto.FavouriteParkingListResponse;
import com.parkio.user.presentation.dto.FavouriteParkingResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated parking favourites API (WP-SPA-04).
 * Gated by {@code parkio.spa.favourites.enabled}.
 */
@RestController
@RequestMapping("/api/v1/places/favourites/parking")
public class FavouriteParkingController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final FavouriteParkingApplicationService favourites;
    private final boolean favouritesEnabled;

    public FavouriteParkingController(
            FavouriteParkingApplicationService favourites,
            @Value("${parkio.spa.favourites.enabled:false}") boolean favouritesEnabled) {
        this.favourites = favourites;
        this.favouritesEnabled = favouritesEnabled;
    }

    @GetMapping
    public FavouriteParkingListResponse list(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        requireEnabled();
        return new FavouriteParkingListResponse(
                favourites.list(requireUserId(userId)).stream()
                        .map(FavouriteParkingResponse::from)
                        .toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FavouriteParkingResponse add(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody CreateFavouriteParkingRequest request) {
        requireEnabled();
        FavouriteParkingTargetKind kind = request.targetKind() == null
                ? FavouriteParkingTargetKind.MUNICIPAL_FACILITY
                : request.targetKind();
        if (kind != FavouriteParkingTargetKind.MUNICIPAL_FACILITY) {
            throw new UserException(UserErrorCode.UNSUPPORTED_FAVOURITE_TARGET);
        }
        return FavouriteParkingResponse.from(
                favourites.addMunicipalFacility(requireUserId(userId), request.targetId()));
    }

    @DeleteMapping("/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable("targetId") UUID targetId) {
        requireEnabled();
        favourites.removeMunicipalFacility(requireUserId(userId), targetId);
    }

    @GetMapping("/status")
    public FavouriteParkingStatusResponse status(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam("targetIds") List<UUID> targetIds) {
        requireEnabled();
        Set<UUID> favourited = favourites.statusFor(requireUserId(userId), targetIds);
        return new FavouriteParkingStatusResponse(favourited.stream().sorted().toList());
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

    public record FavouriteParkingStatusResponse(List<UUID> favouritedTargetIds) {
    }
}
