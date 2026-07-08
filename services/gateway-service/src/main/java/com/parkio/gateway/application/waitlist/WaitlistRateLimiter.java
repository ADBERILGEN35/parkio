package com.parkio.gateway.application.waitlist;

import reactor.core.publisher.Mono;

public interface WaitlistRateLimiter {

    Mono<Void> check(String ipHash, String emailHash);
}
