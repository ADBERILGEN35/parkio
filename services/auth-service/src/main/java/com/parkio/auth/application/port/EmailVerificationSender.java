package com.parkio.auth.application.port;

/** Sends an email verification link. Implementations must not log raw tokens in production. */
public interface EmailVerificationSender {

    void sendVerificationLink(String email, String rawToken);
}
