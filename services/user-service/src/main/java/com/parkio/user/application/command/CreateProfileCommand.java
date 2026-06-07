package com.parkio.user.application.command;

import java.util.UUID;

/** Input for creating a profile (e.g. from a UserRegistered event). */
public record CreateProfileCommand(
        UUID authUserId,
        String email,
        String displayName,
        String phoneNumber,
        String city) {
}
