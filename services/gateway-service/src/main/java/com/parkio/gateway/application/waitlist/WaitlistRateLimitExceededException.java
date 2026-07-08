package com.parkio.gateway.application.waitlist;

public class WaitlistRateLimitExceededException extends RuntimeException {

    public WaitlistRateLimitExceededException() {
        super("Waitlist submission rate limit exceeded.");
    }
}
