package com.parkio.gateway.presentation.waitlist;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResendWaitlistRequest(
        @NotBlank @Email @Size(max = 254) String email) {
}
