package com.parkio.gateway.application.waitlist;

public class WaitlistTokenException extends RuntimeException {

    private final String code;

    public WaitlistTokenException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
