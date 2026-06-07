package com.parkio.user.presentation;

import com.parkio.user.application.UserApplicationService;
import com.parkio.user.application.command.UpdatePreferencesCommand;
import com.parkio.user.application.command.UpdateProfileCommand;
import com.parkio.user.application.command.UpsertVehicleCommand;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.presentation.dto.PreferencesRequest;
import com.parkio.user.presentation.dto.PreferencesResponse;
import com.parkio.user.presentation.dto.ProfileResponse;
import com.parkio.user.presentation.dto.PublicProfileResponse;
import com.parkio.user.presentation.dto.StatsResponse;
import com.parkio.user.presentation.dto.UpdateProfileRequest;
import com.parkio.user.presentation.dto.VehicleRequest;
import com.parkio.user.presentation.dto.VehicleResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User API. Translates HTTP into application commands and results into response
 * DTOs — JPA entities and domain objects never cross this boundary.
 *
 * <p>The authenticated user id is read from the {@code X-User-Id} header for
 * local/service testing. Full gateway/JWT integration is not wired yet; this is
 * the agreed interim contract (the gateway will forward the verified identity).
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final UserApplicationService userService;

    public UserController(UserApplicationService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ProfileResponse getMyProfile(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return ProfileResponse.from(userService.getMyProfile(requireUserId(userId)));
    }

    @PatchMapping("/me")
    public ProfileResponse updateMyProfile(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                           @Valid @RequestBody UpdateProfileRequest request) {
        UpdateProfileCommand command =
                new UpdateProfileCommand(request.displayName(), request.phoneNumber(), request.city());
        return ProfileResponse.from(userService.updateMyProfile(requireUserId(userId), command));
    }

    @GetMapping("/me/preferences")
    public PreferencesResponse getMyPreferences(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return PreferencesResponse.from(userService.getMyPreferences(requireUserId(userId)));
    }

    @PatchMapping("/me/preferences")
    public PreferencesResponse updateMyPreferences(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody PreferencesRequest request) {
        UpdatePreferencesCommand command =
                new UpdatePreferencesCommand(request.preferredRadiusMeters(), request.notificationsEnabled());
        return PreferencesResponse.from(userService.updateMyPreferences(requireUserId(userId), command));
    }

    @GetMapping("/me/vehicle")
    public VehicleResponse getMyVehicle(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return userService.getMyVehicle(requireUserId(userId))
                .map(VehicleResponse::from)
                .orElseGet(VehicleResponse::empty);
    }

    @PutMapping("/me/vehicle")
    public VehicleResponse putMyVehicle(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                        @Valid @RequestBody VehicleRequest request) {
        UpsertVehicleCommand command = new UpsertVehicleCommand(request.vehicleType(), request.plate());
        return VehicleResponse.from(userService.upsertMyVehicle(requireUserId(userId), command));
    }

    @GetMapping("/me/stats")
    public StatsResponse getMyStats(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return StatsResponse.from(userService.getMyStats(requireUserId(userId)));
    }

    /**
     * Public profile lookup. The {@code {userId}} path variable is the platform-wide
     * user id — i.e. the {@code authUserId} — not the internal {@code user_profiles.id},
     * which never leaves this service.
     */
    @GetMapping("/{userId}/public-profile")
    public PublicProfileResponse getPublicProfile(@PathVariable("userId") UUID authUserId) {
        return PublicProfileResponse.from(userService.getPublicProfile(authUserId));
    }

    /** Resolves the authenticated user id from the header; fails closed if absent/invalid. */
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
}
