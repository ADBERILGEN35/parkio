package com.parkio.auth.domain.exception;

/**
 * Stable, domain-level error codes. They are framework-free (no HTTP here);
 * the presentation layer maps them to HTTP status codes and consistent API
 * error bodies (ai-context/04).
 */
public enum AuthErrorCode {
    EMAIL_ALREADY_EXISTS("Email is already registered."),
    INVALID_CREDENTIALS("Invalid email or password."),
    INVALID_REFRESH_TOKEN("Refresh token is invalid or expired."),
    INVALID_VERIFICATION_TOKEN("Email verification token is invalid or expired."),
    INVALID_RESET_TOKEN("Password reset token is invalid or expired."),
    ACCOUNT_NOT_VERIFIED("Please verify your email before signing in."),
    WEAK_PASSWORD("Password does not meet the security requirements."),
    USER_NOT_ACTIVE("Account is not active."),
    USER_NOT_FOUND("User not found."),
    FORBIDDEN("You are not allowed to perform this action."),
    CONFLICT("The request conflicts with the current state."),
    LAST_SUPER_ADMIN("Cannot remove or demote the final SUPER_ADMIN."),
    PRIVILEGE_ESCALATION("This role change is not permitted."),
    INVALID_ADMIN_ACTION("The administrative action is invalid."),
    SESSION_NOT_FOUND("Session not found."),
    BOOTSTRAP_DISABLED("Admin bootstrap is disabled.");

    private final String defaultMessage;

    AuthErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
