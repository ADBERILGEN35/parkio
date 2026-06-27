package com.parkio.user.presentation;

import com.parkio.user.application.UserApplicationService;
import com.parkio.user.application.command.SmartReturnReturnTimeCommand;
import com.parkio.user.application.command.UpdatePreferencesCommand;
import com.parkio.user.application.command.UpdateProfileCommand;
import com.parkio.user.application.command.UpdateSmartReturnSettingsCommand;
import com.parkio.user.application.command.UpsertVehicleCommand;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.presentation.dto.PreferencesRequest;
import com.parkio.user.presentation.dto.PreferencesResponse;
import com.parkio.user.presentation.dto.ProfileResponse;
import com.parkio.user.presentation.dto.PublicProfileResponse;
import com.parkio.user.presentation.dto.SmartReturnSettingsRequest;
import com.parkio.user.presentation.dto.SmartReturnSettingsResponse;
import com.parkio.user.presentation.dto.SmartReturnTodayRequest;
import com.parkio.user.presentation.dto.StatsResponse;
import com.parkio.user.presentation.dto.UpdateProfileRequest;
import com.parkio.user.presentation.dto.VehicleRequest;
import com.parkio.user.presentation.dto.VehicleResponse;
import com.parkio.user.presentation.openapi.StandardApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
@Tag(name = "Users", description = "Profiles, preferences and public profiles")
@StandardApiResponses
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final UserApplicationService userService;
    private final boolean smartReturnEnabled;

    public UserController(UserApplicationService userService,
                          @Value("${parkio.smart-return.enabled:false}") boolean smartReturnEnabled) {
        this.userService = userService;
        this.smartReturnEnabled = smartReturnEnabled;
    }

    @Operation(summary = "Get current user profile")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    public ProfileResponse getMyProfile(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return ProfileResponse.from(userService.getMyProfile(requireUserId(userId)));
    }

    @Operation(summary = "Update current user profile")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/me")
    public ProfileResponse updateMyProfile(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                           @Valid @RequestBody UpdateProfileRequest request) {
        UpdateProfileCommand command =
                new UpdateProfileCommand(request.displayName(), request.phoneNumber(), request.city());
        return ProfileResponse.from(userService.updateMyProfile(requireUserId(userId), command));
    }

    @Operation(summary = "Get current user preferences")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me/preferences")
    public PreferencesResponse getMyPreferences(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return PreferencesResponse.from(userService.getMyPreferences(requireUserId(userId)));
    }

    @Operation(summary = "Update current user preferences")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/me/preferences")
    public PreferencesResponse updateMyPreferences(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody PreferencesRequest request) {
        UpdatePreferencesCommand command =
                new UpdatePreferencesCommand(request.preferredRadiusMeters(), request.notificationsEnabled());
        return PreferencesResponse.from(userService.updateMyPreferences(requireUserId(userId), command));
    }

    @Operation(summary = "Get Smart Return settings")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me/smart-return")
    public SmartReturnSettingsResponse getSmartReturn(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        requireSmartReturnEnabled();
        return SmartReturnSettingsResponse.from(userService.getMySmartReturn(requireUserId(userId)));
    }

    @Operation(summary = "Update Smart Return settings")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/me/smart-return/settings")
    public SmartReturnSettingsResponse updateSmartReturnSettings(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody SmartReturnSettingsRequest request) {
        requireSmartReturnEnabled();
        UpdateSmartReturnSettingsCommand command = new UpdateSmartReturnSettingsCommand(
                request.enabled(), request.homeLatitude(), request.homeLongitude(), request.homeLabel(),
                request.defaultReturnTime(), request.reminderLeadMinutes());
        return SmartReturnSettingsResponse.from(userService.updateMySmartReturnSettings(requireUserId(userId), command));
    }

    @Operation(summary = "Answer Smart Return prompt: left by car")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/me/smart-return/today/left-by-car")
    public SmartReturnSettingsResponse leftByCar(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody SmartReturnTodayRequest request) {
        requireSmartReturnEnabled();
        return SmartReturnSettingsResponse.from(userService.markSmartReturnLeftByCar(
                requireUserId(userId), new SmartReturnReturnTimeCommand(request.expectedReturnAt())));
    }

    @Operation(summary = "Answer Smart Return prompt: not by car")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/me/smart-return/today/not-by-car")
    public SmartReturnSettingsResponse notByCar(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        requireSmartReturnEnabled();
        return SmartReturnSettingsResponse.from(userService.markSmartReturnNotByCar(requireUserId(userId)));
    }

    @Operation(summary = "Edit today's Smart Return expected return time")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/me/smart-return/today/return-time")
    public SmartReturnSettingsResponse updateReturnTime(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody SmartReturnTodayRequest request) {
        requireSmartReturnEnabled();
        return SmartReturnSettingsResponse.from(userService.updateSmartReturnTime(
                requireUserId(userId), new SmartReturnReturnTimeCommand(request.expectedReturnAt())));
    }

    @Operation(summary = "Cancel today's Smart Return reminder")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/me/smart-return/today/cancel")
    public SmartReturnSettingsResponse cancelSmartReturnToday(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        requireSmartReturnEnabled();
        return SmartReturnSettingsResponse.from(userService.cancelSmartReturnToday(requireUserId(userId)));
    }

    @Operation(summary = "Get current user vehicle")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me/vehicle")
    public VehicleResponse getMyVehicle(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return userService.getMyVehicle(requireUserId(userId))
                .map(VehicleResponse::from)
                .orElseGet(VehicleResponse::empty);
    }

    @Operation(summary = "Create or update current user vehicle")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/me/vehicle")
    public VehicleResponse putMyVehicle(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                        @Valid @RequestBody VehicleRequest request) {
        UpsertVehicleCommand command = new UpsertVehicleCommand(request.vehicleType(), request.plate());
        return VehicleResponse.from(userService.upsertMyVehicle(requireUserId(userId), command));
    }

    @Operation(summary = "Get current user stats")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me/stats")
    public StatsResponse getMyStats(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return StatsResponse.from(userService.getMyStats(requireUserId(userId)));
    }

    /**
     * Public profile lookup. The {@code {userId}} path variable is the platform-wide
     * user id — i.e. the {@code authUserId} — not the internal {@code user_profiles.id},
     * which never leaves this service.
     */
    @Operation(summary = "Get public profile by user id")
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

    private void requireSmartReturnEnabled() {
        if (!smartReturnEnabled) {
            throw new UserException(UserErrorCode.SMART_RETURN_DISABLED);
        }
    }
}
