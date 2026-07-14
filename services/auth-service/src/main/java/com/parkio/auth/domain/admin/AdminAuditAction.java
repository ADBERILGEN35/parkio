package com.parkio.auth.domain.admin;

/**
 * Administrative action types recorded in the audit trail.
 */
public enum AdminAuditAction {
    ADMIN_USER_SUSPENDED,
    ADMIN_USER_REACTIVATED,
    ADMIN_USER_SESSIONS_REVOKED,
    ADMIN_USER_ROLE_GRANTED,
    ADMIN_USER_ROLE_REVOKED,
    ADMIN_VERIFICATION_RESENT,
    ADMIN_SESSION_REVOKED,
    ADMIN_BOOTSTRAP_SUPER_ADMIN
}
