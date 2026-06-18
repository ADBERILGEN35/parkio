package com.parkio.auth.application.port;

/** Sends password reset links. Implementations must not log raw tokens in production. */
public interface PasswordResetEmailSender {

    void sendResetLink(String email, String rawToken);
}
