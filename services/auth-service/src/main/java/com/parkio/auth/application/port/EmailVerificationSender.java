package com.parkio.auth.application.port;

import com.parkio.auth.domain.EmailLocale;

/** Sends an email verification link. Implementations must not log raw tokens in production. */
public interface EmailVerificationSender {

    void sendVerificationLink(String email, String rawToken, EmailLocale locale);

    default void sendVerificationLink(String email, String rawToken) {
        sendVerificationLink(email, rawToken, EmailLocale.TR);
    }
}
