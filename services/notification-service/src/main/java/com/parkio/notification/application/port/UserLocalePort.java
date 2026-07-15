package com.parkio.notification.application.port;

import com.parkio.notification.domain.NotificationLocale;
import java.util.UUID;

/**
 * Resolves a recipient's preferred UI locale (from user-service preferences).
 * Implementations must fail soft — never block notification creation when the
 * upstream call fails; return {@link NotificationLocale#DEFAULT} instead.
 */
public interface UserLocalePort {

    NotificationLocale resolvePreferredLocale(UUID userId);
}