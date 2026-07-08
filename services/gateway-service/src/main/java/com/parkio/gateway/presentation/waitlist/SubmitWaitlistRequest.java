package com.parkio.gateway.presentation.waitlist;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record SubmitWaitlistRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @PastOrPresent Instant consentTimestamp,
        @Size(max = 120)
        @Pattern(regexp = "^(?!\\s*[+-]?\\d+(?:\\.\\d+)?\\s*,\\s*[+-]?\\d+(?:\\.\\d+)?\\s*$).*$")
        String city,
        @Pattern(regexp = "driver|tester|partner") String role,
        @NotBlank @Pattern(regexp = "parkio\\.dev-landing") String source) {
}
