package com.parkio.auth.presentation.dto;

import com.parkio.auth.domain.RegistrationMode;

/** Public bootstrap state only; no invite or operator configuration is exposed. */
public record RegistrationModeResponse(RegistrationMode mode) {}
