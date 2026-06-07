package com.parkio.auth.application.port;

/** Port for generating opaque, high-entropy refresh token values. */
public interface SecureTokenGenerator {

    String generate();
}
