package com.parkio.notification.application.command;

import com.parkio.notification.domain.DevicePlatform;
import java.util.UUID;

/** Request to register (or re-activate) a push device token for a user. */
public record RegisterDeviceTokenCommand(UUID userId, String token, DevicePlatform platform) {
}
