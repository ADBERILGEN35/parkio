package com.parkio.auth.application.command;

import com.parkio.auth.domain.EmailLocale;

public record ResendVerificationCommand(String email, EmailLocale locale) {

    public ResendVerificationCommand(String email) {
        this(email, EmailLocale.TR);
    }
}
