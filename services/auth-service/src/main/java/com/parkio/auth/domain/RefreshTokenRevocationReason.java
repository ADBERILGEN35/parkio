package com.parkio.auth.domain;

public enum RefreshTokenRevocationReason {
    ROTATED,
    LOGOUT,
    REUSE_DETECTED,
    EXPIRED_CLEANUP,
    ADMIN_REVOKED
}
