package com.parkio.auth.application.command;

/**
 * @param clientKey the gateway-resolved client IP for throttling (CL-F15), or {@code null}
 *     when the request carried none (the shared unknown-client bucket is used)
 */
public record LoginCommand(String email, String rawPassword, String clientKey) {
}
