package com.parkio.gateway.application.waitlist;

public class WaitlistFullNameException extends RuntimeException {

    private final String code;

    public WaitlistFullNameException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
