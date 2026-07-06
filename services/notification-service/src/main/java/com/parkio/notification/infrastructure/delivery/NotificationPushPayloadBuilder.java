package com.parkio.notification.infrastructure.delivery;

import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Maps in-app notification metadata to provider-safe push data (route + deeplink). */
public final class NotificationPushPayloadBuilder {

    static final String DATA_ROUTE = "route";
    static final String DATA_DEEPLINK = "deeplink";
    static final String DATA_NOTIFICATION_TYPE = "notificationType";
    private static final Pattern SPOT_DEEPLINK = Pattern.compile("^/spots/([0-9a-fA-F-]{36})$");

    private NotificationPushPayloadBuilder() {
    }

    public static Map<String, String> build(Notification notification) {
        Map<String, String> metadata = notification.metadata();
        Map<String, String> data = new LinkedHashMap<>();
        data.put(DATA_NOTIFICATION_TYPE, notification.type().name());
        String deeplink = metadata.get("deeplink");
        if (deeplink != null && !deeplink.isBlank()) {
            data.put(DATA_DEEPLINK, deeplink);
        }
        data.put(DATA_ROUTE, mobileRoute(notification.type(), metadata));
        return Map.copyOf(data);
    }

    static String mobileRoute(NotificationType type, Map<String, String> metadata) {
        String deeplink = metadata.get("deeplink");
        if (deeplink != null) {
            String mapped = mapWebDeeplinkToMobileRoute(deeplink);
            if (mapped != null) {
                return mapped;
            }
        }
        return switch (type) {
            case LEVEL_UP, POINT_EARNED -> "/(main)/impact";
            case WARNING -> "/(main)/reports";
            case SMART_RETURN_PROMPT -> "/(main)/smart-return";
            case SMART_RETURN_AVAILABLE -> "/(main)/map";
            case NEARBY_PARKING -> "/(main)/(tabs)/home";
            case SYSTEM -> "/(main)/(tabs)/notifications";
        };
    }

    private static String mapWebDeeplinkToMobileRoute(String deeplink) {
        String path = deeplink.split("\\?", 2)[0];
        return switch (path) {
            case "/map" -> "/(main)/map";
            case "/profile" -> "/(main)/(tabs)/profile";
            case "/notifications" -> "/(main)/(tabs)/notifications";
            case "/reports" -> "/(main)/reports";
            case "/moderation" -> "/(main)/moderation";
            case "/my-spots" -> "/(main)/my-spots";
            case "/upload" -> "/(main)/upload";
            case "/gamification" -> "/(main)/impact";
            case "/leaderboard" -> "/(main)/leaderboard";
            default -> {
                var spotMatcher = SPOT_DEEPLINK.matcher(path);
                if (spotMatcher.matches()) {
                    yield "/(main)/spots/" + spotMatcher.group(1);
                }
                if (deeplink.contains("smart-return")) {
                    yield "/(main)/smart-return";
                }
                if (deeplink.contains("smartReturn=1")) {
                    yield "/(main)/map";
                }
                yield null;
            }
        };
    }
}
