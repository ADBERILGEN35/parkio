package com.parkio.gateway.infrastructure.security;

import java.security.interfaces.RSAPublicKey;
import reactor.core.publisher.Mono;

public interface JwksKeyResolver {

    Mono<RSAPublicKey> resolve(String keyId);
}
