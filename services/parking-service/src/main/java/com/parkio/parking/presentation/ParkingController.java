package com.parkio.parking.presentation;

import com.parkio.parking.application.ParkingApplicationService;
import com.parkio.parking.application.command.CreateSpotCommand;
import com.parkio.parking.application.command.SearchNearbyQuery;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.presentation.dto.CreateSpotRequest;
import com.parkio.parking.presentation.dto.PublicSpotResponse;
import com.parkio.parking.presentation.dto.SpotResponse;
import com.parkio.parking.presentation.dto.VerifySpotRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parking API. Translates HTTP into application commands and domain objects into
 * response DTOs — JPA entities never cross this boundary.
 *
 * <p>The authenticated user id is read from the {@code X-User-Id} header, which the
 * gateway strips from client input and re-injects after verifying the JWT. Requests
 * without a valid id fail closed.
 */
@RestController
@RequestMapping("/api/v1/parking")
public class ParkingController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final ParkingApplicationService parkingService;

    public ParkingController(ParkingApplicationService parkingService) {
        this.parkingService = parkingService;
    }

    @PostMapping("/spots")
    public ResponseEntity<SpotResponse> createSpot(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody CreateSpotRequest request) {
        UUID ownerUserId = requireUserId(userId);
        CreateSpotCommand command = new CreateSpotCommand(ownerUserId, request.mediaId(),
                request.latitude(), request.longitude(), request.addressText(), request.description(),
                request.manualLocationEdited(), request.suitableVehicleTypes(), request.parkingContext(),
                request.legalStatus(), request.violationReasons());
        ParkingSpot spot = parkingService.createSpot(command);
        return ResponseEntity.created(URI.create("/api/v1/parking/spots/" + spot.id()))
                .body(SpotResponse.from(spot));
    }

    @GetMapping("/spots/nearby")
    public List<PublicSpotResponse> searchNearby(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestParam(value = "radius", required = false) Double radius,
            @RequestParam(value = "limit", required = false) Integer limit) {
        UUID searcherUserId = requireUserId(userId);
        SearchNearbyQuery query = new SearchNearbyQuery(searcherUserId, lat, lng, radius, limit);
        return parkingService.searchNearby(query).stream().map(PublicSpotResponse::from).toList();
    }

    @GetMapping("/spots/{spotId}")
    public PublicSpotResponse getSpot(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                      @PathVariable("spotId") UUID spotId) {
        UUID viewerUserId = requireUserId(userId);
        return PublicSpotResponse.from(parkingService.getSpotForViewer(spotId, viewerUserId));
    }

    @PostMapping("/spots/{spotId}/verify")
    public PublicSpotResponse verifySpot(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                         @PathVariable("spotId") UUID spotId,
                                         @Valid @RequestBody VerifySpotRequest request) {
        UUID verifierUserId = requireUserId(userId);
        return PublicSpotResponse.from(parkingService.verifySpot(spotId, verifierUserId, request.result()));
    }

    @PostMapping("/spots/{spotId}/claim")
    public PublicSpotResponse claimSpot(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                        @PathVariable("spotId") UUID spotId) {
        UUID claimerUserId = requireUserId(userId);
        return PublicSpotResponse.from(parkingService.claimSpot(spotId, claimerUserId));
    }

    @GetMapping("/my-spots")
    public List<SpotResponse> listMySpots(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        UUID ownerUserId = requireUserId(userId);
        return parkingService.listMySpots(ownerUserId).stream().map(SpotResponse::from).toList();
    }

    @GetMapping("/my-spots/{spotId}")
    public SpotResponse getMySpot(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                  @PathVariable("spotId") UUID spotId) {
        UUID ownerUserId = requireUserId(userId);
        return SpotResponse.from(parkingService.getMySpot(ownerUserId, spotId));
    }

    /** Resolves the authenticated user id from the header; fails closed if absent/invalid. */
    private static UUID requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new ParkingException(ParkingErrorCode.MISSING_USER_ID, "Missing authenticated user id.");
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new ParkingException(ParkingErrorCode.MISSING_USER_ID, "Invalid authenticated user id.");
        }
    }
}
