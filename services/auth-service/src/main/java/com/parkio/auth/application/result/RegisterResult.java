package com.parkio.auth.application.result;

import com.parkio.auth.domain.AuthUser;
import java.time.Instant;

public record RegisterResult(AuthUser user, Instant verificationExpiresAt) {
}
