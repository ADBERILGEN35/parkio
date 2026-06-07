package com.parkio.auth.application.result;

import java.time.Instant;

/** A signed access token plus the instant it expires. */
public record IssuedAccessToken(String token, Instant expiresAt) {
}
