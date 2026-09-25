package com.parkio.gateway.application.waitlist;

/** Bounded waitlist full-name validation failure (code is a stable machine token). */
public class WaitlistFullNameException extends RuntimeException {

    private final String code;

    public WaitlistFullNameException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public String getCode() {
        return code;
    }
}