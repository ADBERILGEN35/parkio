package com.parkio.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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

    @Test
    void mapsWebGamificationDeeplinkToMobileImpactRoute() {
        assertThat(NotificationPushPayloadBuilder.mobileRoute(
                        NotificationType.POINT_EARNED, Map.of("deeplink", "/gamification")))
                .isEqualTo("/(main)/impact");
    }

    @Test
    void mapsSpotDeeplinkToMobileSpotDetailRoute() {
        UUID spotId = UUID.randomUUID();

        assertThat(NotificationPushPayloadBuilder.mobileRoute(
                        NotificationType.WARNING, Map.of("deeplink", "/spots/" + spotId)))
                .isEqualTo("/(main)/spots/" + spotId);
    }

    @Test
    void unknownDeeplinkFallsBackByNotificationType() {
        assertThat(NotificationPushPayloadBuilder.mobileRoute(
                        NotificationType.WARNING, Map.of("deeplink", "/unknown")))
                .isEqualTo("/(main)/reports");
    }

    @Test
    void buildIncludesNotificationTypeWebDeeplinkAndMobileRoute() {
        UUID spotId = UUID.randomUUID();
        Notification notification = Notification.create(
                UUID.randomUUID(),
                NotificationType.WARNING,
                NotificationChannel.IN_APP,
                "Heads up",
                "Your parking spot was rejected.",
                Map.of(
                        "deeplink", "/spots/" + spotId,
                        "messageKey", "spotRejectedIllegal"),
                Instant.parse("2026-06-07T12:00:00Z"));

        assertThat(NotificationPushPayloadBuilder.build(notification))
                .containsEntry("notificationType", "WARNING")
                .containsEntry("messageKey", "spotRejectedIllegal")
                .containsEntry("deeplink", "/spots/" + spotId)
                .containsEntry("route", "/(main)/spots/" + spotId);
    }

    @Test
    void buildForwardsMessageKeyAndTemplateVariables() {
        Notification notification = Notification.create(
                UUID.randomUUID(),
                NotificationType.POINT_EARNED,
                NotificationChannel.IN_APP,
                "You earned points",
                "You earned 5 points. Total: 20.",
                Map.of(
                        "messageKey", "pointEarned",
                        "points", "5",
                        "totalPoints", "20",
                        "deeplink", "/gamification"),
                Instant.parse("2026-06-07T12:00:00Z"));

        assertThat(NotificationPushPayloadBuilder.build(notification))
                .containsEntry("messageKey", "pointEarned")
                .containsEntry("points", "5")
                .containsEntry("totalPoints", "20")
                .containsEntry("notificationType", "POINT_EARNED");
    }
}
