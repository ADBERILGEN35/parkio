package com.parkio.auth.application.command;

public record LoginCommand(String email, String rawPassword) {
}
