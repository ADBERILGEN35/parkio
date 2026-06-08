package com.parkio.notification.presentation.dto;

import com.parkio.notification.domain.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request to register a push device token. */
public record RegisterDeviceTokenRequest(
        @NotBlank @Size(max = 512) String token,
        @NotNull DevicePlatform platform) {
}
