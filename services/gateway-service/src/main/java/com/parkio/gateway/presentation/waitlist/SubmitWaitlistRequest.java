package com.parkio.gateway.presentation.waitlist;

import com.parkio.gateway.application.waitlist.WaitlistFullName;
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
        /**
         * The subscriber ticked the consent checkbox. Required to be {@code true} when
         * {@code parkio.waitlist.consent-required=true} (the default); an explicit {@code false} is
         * refused in every mode. Nullable at the Bean Validation layer so the application service can
         * answer with the waitlist error codes instead of a generic validation error (CL-F18).
         */
        Boolean consent,
        /**
         * Version id of the consent text shown, from
         * {@link com.parkio.gateway.application.waitlist.WaitlistConsentText}. Required when consent
         * is required; only registered versions are accepted.
         */
        @Size(max = 64) @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$") String consentTextVersion,
        /**
         * Optional at the Bean Validation layer for marketing/API rollout compatibility.
         * When {@code parkio.waitlist.full-name-required=true}, the application service
         * rejects missing/blank values. Format is always enforced when present.
         */
        @Size(max = WaitlistFullName.MAX_LENGTH) String fullName,
        @Size(max = 120)
        @Pattern(regexp = "^(?!\\s*[+-]?\\d+(?:\\.\\d+)?\\s*,\\s*[+-]?\\d+(?:\\.\\d+)?\\s*$).*$")
        String city,
        @Pattern(regexp = "driver|tester|partner") String role,
        @NotBlank @Pattern(regexp = "parkio\\.dev-landing") String source,
        @Pattern(regexp = "tr|en") String locale) {
}
