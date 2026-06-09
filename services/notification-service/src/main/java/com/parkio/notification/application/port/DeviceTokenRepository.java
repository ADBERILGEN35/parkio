package com.parkio.notification.application.port;

import com.parkio.notification.domain.DeviceToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link DeviceToken}. */
public interface DeviceTokenRepository {

    DeviceToken save(DeviceToken deviceToken);

    Optional<DeviceToken> findById(UUID id);

    Optional<DeviceToken> findByUserIdAndToken(UUID userId, String token);

    /** Active (non-deactivated) tokens for a user — the push delivery targets. */
    List<DeviceToken> findActiveByUserId(UUID userId);
}
