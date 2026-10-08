package com.parkio.auth.application.command;

/**
 * @param clientKey the client completing the reset ({@code LoginClientKeys}); it becomes a known client
 *     of the account for the login throttle. Null when unknown.
 */
public record ResetPasswordCommand(String rawToken, String newPassword, String clientKey) {

    public ResetPasswordCommand(String rawToken, String newPassword) {
        this(rawToken, newPassword, null);
    }
}
