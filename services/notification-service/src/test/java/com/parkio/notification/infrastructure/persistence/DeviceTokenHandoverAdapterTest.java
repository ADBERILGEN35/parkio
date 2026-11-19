package com.parkio.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.notification.application.port.DeviceTokenHandover;
import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.domain.DeviceToken;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The handover update against the generated H2 schema (no advisory lock there). The PostgreSQL
 * lock and the Flyway schema are covered by {@code DeviceTokenHandoverPostgresIT}.
 */
@SpringBootTest
@Transactional
class DeviceTokenHandoverAdapterTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T08:00:00Z");
    private static final Instant HANDED_OVER_AT = Instant.parse("2026-11-19T09:30:00Z");
    private static final String TOKEN = "ExponentPushToken[handover-adapter]";

    @Autowired
    private DeviceTokenHandover handover;

    @Autowired
    private DeviceTokenRepository deviceTokens;

    @Test
    void deactivatesOnlyOtherUsersActiveRowsOfTheToken() {
        UUID caller = UUID.randomUUID();
        DeviceToken previousHolder = store(UUID.randomUUID(), TOKEN, true);
        DeviceToken earlierHolder = store(UUID.randomUUID(), TOKEN, false);
        DeviceToken callersRow = store(caller, TOKEN, true);
        DeviceToken otherDevice = store(UUID.randomUUID(), "ExponentPushToken[other-device]", true);

        int deactivated = handover.deactivateForOtherUsers(TOKEN, caller, HANDED_OVER_AT);

        assertThat(deactivated).isEqualTo(1);
        DeviceToken handedOver = reload(previousHolder);
        assertThat(handedOver.active()).isFalse();
        assertThat(handedOver.updatedAt()).isEqualTo(HANDED_OVER_AT);
        assertThat(handedOver.version()).isEqualTo(previousHolder.version() + 1);
        assertUnchanged(earlierHolder);
        assertUnchanged(callersRow);
        assertUnchanged(otherDevice);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void refusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> handover.deactivateForOtherUsers(TOKEN, UUID.randomUUID(), HANDED_OVER_AT))
                .isInstanceOf(IllegalStateException.class);
    }

    private DeviceToken store(UUID userId, String token, boolean active) {
        DeviceToken deviceToken = DeviceToken.register(userId, token, DevicePlatform.ANDROID, REGISTERED_AT);
        if (!active) {
            deviceToken.deactivate(REGISTERED_AT);
        }
        return deviceTokens.save(deviceToken);
    }

    private DeviceToken reload(DeviceToken deviceToken) {
        return deviceTokens.findById(deviceToken.id()).orElseThrow();
    }

    private void assertUnchanged(DeviceToken before) {
        DeviceToken after = reload(before);
        assertThat(after.active()).isEqualTo(before.active());
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(after.version()).isEqualTo(before.version());
    }
}
