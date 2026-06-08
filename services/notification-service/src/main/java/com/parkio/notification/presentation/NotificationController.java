package com.parkio.notification.presentation;

import com.parkio.notification.application.NotificationApplicationService;
import com.parkio.notification.application.command.RegisterDeviceTokenCommand;
import com.parkio.notification.application.command.UpdatePreferencesCommand;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.exception.NotificationErrorCode;
import com.parkio.notification.domain.exception.NotificationException;
import com.parkio.notification.presentation.dto.DeviceTokenResponse;
import com.parkio.notification.presentation.dto.NotificationResponse;
import com.parkio.notification.presentation.dto.PreferencesRequest;
import com.parkio.notification.presentation.dto.PreferencesResponse;
import com.parkio.notification.presentation.dto.RegisterDeviceTokenRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification API. Translates HTTP into application calls and domain objects into
 * response DTOs — JPA entities never cross this boundary.
 *
 * <p>The authenticated user id is read from the {@code X-User-Id} header
 * (gateway-injected). Requests without a valid id fail closed. A user may only
 * read/modify their own notifications and device tokens.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final int RECENT_LIMIT = 50;

    private final NotificationApplicationService notificationService;

    public NotificationController(NotificationApplicationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/me")
    public List<NotificationResponse> getMyNotifications(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return notificationService.getMyNotifications(requireUserId(userId), RECENT_LIMIT).stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @PatchMapping("/{notificationId}/read")
    public NotificationResponse markRead(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                         @PathVariable("notificationId") UUID notificationId) {
        return NotificationResponse.from(notificationService.markRead(requireUserId(userId), notificationId));
    }

    @PostMapping("/device-token")
    public ResponseEntity<DeviceTokenResponse> registerDeviceToken(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody RegisterDeviceTokenRequest request) {
        DeviceToken token = notificationService.registerDeviceToken(
                new RegisterDeviceTokenCommand(requireUserId(userId), request.token(), request.platform()));
        return ResponseEntity
                .created(URI.create("/api/v1/notifications/device-token/" + token.id()))
                .body(DeviceTokenResponse.from(token));
    }

    @DeleteMapping("/device-token/{tokenId}")
    public ResponseEntity<Void> deleteDeviceToken(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable("tokenId") UUID tokenId) {
        notificationService.deactivateDeviceToken(requireUserId(userId), tokenId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/preferences")
    public PreferencesResponse getMyPreferences(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return PreferencesResponse.from(notificationService.getMyPreferences(requireUserId(userId)));
    }

    @PatchMapping("/me/preferences")
    public PreferencesResponse updateMyPreferences(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestBody PreferencesRequest request) {
        UpdatePreferencesCommand command =
                new UpdatePreferencesCommand(request.pushEnabled(), request.emailEnabled(), request.inAppEnabled());
        return PreferencesResponse.from(notificationService.updateMyPreferences(requireUserId(userId), command));
    }

    /** Resolves the authenticated user id from the header; fails closed if absent/invalid. */
    private static UUID requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new NotificationException(NotificationErrorCode.MISSING_USER_ID, "Missing authenticated user id.");
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new NotificationException(NotificationErrorCode.MISSING_USER_ID, "Invalid authenticated user id.");
        }
    }
}
