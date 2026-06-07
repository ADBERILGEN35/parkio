package com.parkio.user.application.command;

/** Partial profile update; {@code null} fields mean "leave unchanged". */
public record UpdateProfileCommand(String displayName, String phoneNumber, String city) {
}
