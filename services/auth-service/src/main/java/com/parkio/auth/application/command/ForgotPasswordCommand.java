package com.parkio.auth.application.command;

import com.parkio.auth.domain.EmailLocale;

public record ForgotPasswordCommand(String email, EmailLocale locale) {

    public ForgotPasswordCommand(String email) {
        this(email, EmailLocale.TR);
    }
}
