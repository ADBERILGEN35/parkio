package com.parkio.gateway.presentation.waitlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WaitlistTokenRequest(@NotBlank @Size(max = 256) String token) {
}
