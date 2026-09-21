package com.parkio.gateway.application.waitlist;

/**
 * Raised when waitlist admissions (submit/resend) are disabled by configuration.
 * Does not apply to confirm or withdraw of already-issued tokens.
 */
public class WaitlistAdmissionsDisabledException extends RuntimeException {

    public WaitlistAdmissionsDisabledException(String message) {
        super(message);
    }
}
