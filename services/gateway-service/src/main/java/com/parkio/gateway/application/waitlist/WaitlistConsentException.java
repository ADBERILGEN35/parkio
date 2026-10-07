package com.parkio.gateway.application.waitlist;

/**
 * The submission did not record a usable consent: {@code WAITLIST_CONSENT_REQUIRED} when consent
 * is missing or false, {@code WAITLIST_CONSENT_VERSION_INVALID} when the consent text version is
 * missing or not registered.
 */
public class WaitlistConsentException extends RuntimeException {

    private final String code;

    public WaitlistConsentException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
