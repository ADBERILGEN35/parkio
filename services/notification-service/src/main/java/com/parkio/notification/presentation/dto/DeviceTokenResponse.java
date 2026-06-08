package com.parkio.notification.presentation.dto;

import com.parkio.notification.domain.DeviceToken;
import java.time.Instant;
import java.util.UUID;

/** A registered device token. The raw token value is not echoed back. */
public record DeviceTokenResponse(UUID id, String platform, boolean active, Instant createdAt) {

    public static DeviceTokenResponse from(DeviceToken t) {
        return new DeviceTokenResponse(t.id(), t.platform().name(), t.active(), t.createdAt());
    }
}
