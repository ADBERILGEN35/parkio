package com.parkio.auth.application.command;

import java.util.UUID;

/**
 * @param clientKey the gateway-resolved client of the request ({@code LoginClientKeys}); the current-password
 *     check is throttled for this (account, client) pair on the login throttle's budget (H-06). Null or blank
 *     when unknown: the account's shared unknown-client pair is used.
 */
public record ChangePasswordCommand(UUID userId, String currentPassword, String newPassword, String clientKey) {

    public ChangePasswordCommand(UUID userId, String currentPassword, String newPassword) {
        this(userId, currentPassword, newPassword, null);
    }
}
