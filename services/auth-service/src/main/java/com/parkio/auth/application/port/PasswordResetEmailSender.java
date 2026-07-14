package com.parkio.auth.application.port;

import com.parkio.auth.domain.EmailLocale;

/** Sends password reset links. Implementations must not log raw tokens in production. */
public interface PasswordResetEmailSender {

    void sendResetLink(String email, String rawToken, EmailLocale locale);

    default void sendResetLink(String email, String rawToken) {
        sendResetLink(email, rawToken, EmailLocale.TR);
    }
}
