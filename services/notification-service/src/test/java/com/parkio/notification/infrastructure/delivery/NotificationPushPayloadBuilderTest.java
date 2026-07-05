package com.parkio.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.notification.domain.NotificationType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationPushPayloadBuilderTest {

    @Test
    void mapsGamificationNotificationsToImpactRoute() {
        assertThat(NotificationPushPayloadBuilder.mobileRoute(NotificationType.LEVEL_UP, Map.of()))
                .isEqualTo("/(main)/impact");
    }

    @Test
    void mapsWebDeeplinkToMobileRoute() {
        assertThat(NotificationPushPayloadBuilder.mobileRoute(
                        NotificationType.SYSTEM, Map.of("deeplink", "/map?smartReturn=1")))
                .isEqualTo("/(main)/map");
    }
}