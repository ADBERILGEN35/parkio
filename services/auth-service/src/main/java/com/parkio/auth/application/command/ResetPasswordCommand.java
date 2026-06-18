package com.parkio.auth.application.command;

public record ResetPasswordCommand(String rawToken, String newPassword) {
}
