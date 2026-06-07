package com.parkio.auth.application.command;

/** Validated registration input (validation happens at the presentation edge). */
public record RegisterCommand(String email, String rawPassword) {
}
