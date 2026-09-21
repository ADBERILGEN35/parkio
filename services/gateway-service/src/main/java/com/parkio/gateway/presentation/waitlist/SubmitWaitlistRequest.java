package com.parkio.gateway.presentation.waitlist;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * {@code consentTimestamp} is the <em>client-asserted</em> consent event time.
 * Strict {@code @PastOrPresent} is intentionally not used: small client/server
 * clock skew would reject legitimate browser submits. Bounded skew is enforced
 * in {@link com.parkio.gateway.application.waitlist.WaitlistApplicationService}
 * using the injectable {@link java.time.Clock}; the gateway persists server
 * receipt time as authoritative consent evidence.
 */
public record SubmitWaitlistRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull Instant consentTimestamp,
        @Size(max = 120)
        @Pattern(regexp = "^(?!\\s*[+-]?\\d+(?:\\.\\d+)?\\s*,\\s*[+-]?\\d+(?:\\.\\d+)?\\s*$).*$")
        String city,
        @Pattern(regexp = "driver|tester|partner") String role,
        @NotBlank @Pattern(regexp = "parkio\\.dev-landing") String source,
        @Pattern(regexp = "tr|en") String locale) {
}
