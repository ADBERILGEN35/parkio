package com.parkio.auth.application.command;

import com.parkio.auth.domain.EmailLocale;

/** Validated registration input (validation happens at the presentation edge). */
public record RegisterCommand(String email, String rawPassword, EmailLocale locale) {

    public RegisterCommand(String email, String rawPassword) {
        this(email, rawPassword, EmailLocale.TR);
    }
}
