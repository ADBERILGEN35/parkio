package com.parkio.notification.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-code TR/EN catalog for notification title/body. English strings match the
 * historical English DB templates and prose; Turkish matches the web/mobile
 * product i18n tone. Clients can re-render from {@code messageKey} + variables
 * stored in notification metadata.
 */
public final class LocalizedNotificationCatalog {

    private LocalizedNotificationCatalog() {
    }

    /**
     * Renders title/body for a structured notification. {@code messageKey} selects
     * the template; {@code variables} supply placeholders (and locale-sensitive
     * labels such as direction/outcome are resolved here when needed).
     */
    public static NotificationTemplate.RenderedContent render(
            String messageKey, NotificationLocale locale, Map<String, String> variables) {
        Objects.requireNonNull(messageKey, "messageKey");
        NotificationLocale resolved = locale == null ? NotificationLocale.DEFAULT : locale;
        Map<String, String> vars = enrichVariables(messageKey, resolved, variables);
        Template template = templateFor(messageKey, resolved);
        return new NotificationTemplate.RenderedContent(
                substitute(template.title(), vars),
                substitute(template.body(), vars));
    }

    /** Default messageKey derived from {@link NotificationType} when callers omit one. */
    public static String defaultMessageKey(NotificationType type) {
        return switch (type) {
            case LEVEL_UP -> "levelUp";
            case POINT_EARNED -> "pointEarned";
            case SMART_RETURN_PROMPT -> "smartReturnPrompt";
            case SMART_RETURN_AVAILABLE -> "smartReturnAvailable";
            case NEARBY_PARKING -> "nearbyParking";
            case WARNING, SYSTEM -> null;
        };
    }

    private static Map<String, String> enrichVariables(
            String messageKey, NotificationLocale locale, Map<String, String> variables) {
        Map<String, String> enriched = new LinkedHashMap<>();
        if (variables != null) {
            enriched.putAll(variables);
        }
        if ("trustChanged".equals(messageKey)) {
            String direction = enriched.get("direction");
            enriched.put("directionLabel", directionLabel(locale, direction));
        }
        if ("appealResolved".equals(messageKey)) {
            String outcome = enriched.get("outcome");
            enriched.put("outcomeLabel", outcomeLabel(locale, outcome));
        }
        return enriched;
    }

    private static String directionLabel(NotificationLocale locale, String direction) {
        boolean increased = "increased".equals(direction);
        if (locale == NotificationLocale.TR) {
            return increased ? "yükseldi" : "düştü";
        }
        return increased ? "increased" : "decreased";
    }

    private static String outcomeLabel(NotificationLocale locale, String outcome) {
        boolean accepted = "accepted".equals(outcome);
        if (locale == NotificationLocale.TR) {
            return accepted ? "kabul edildi" : "reddedildi";
        }
        return accepted ? "accepted" : "rejected";
    }

    private static Template templateFor(String messageKey, NotificationLocale locale) {
        return switch (messageKey) {
            case "levelUp" -> locale == NotificationLocale.TR
                    ? new Template("Seviye atladınız!", "Tebrikler — {level}. seviyeye ulaştınız.")
                    : new Template("Level up!", "Congratulations — you reached level {level}.");
            case "pointEarned" -> locale == NotificationLocale.TR
                    ? new Template("Puan kazandınız", "{points} puan kazandınız. Toplam: {totalPoints}.")
                    : new Template("You earned points", "You earned {points} points. Total: {totalPoints}.");
            case "smartReturnPrompt" -> locale == NotificationLocale.TR
                    ? new Template(
                            "Bugün araç kullanacak mısınız?",
                            "Dönmeden önce park kontrolü isteyip istemediğinizi Parkio'ya bildirin.")
                    : new Template(
                            "Are you driving today?",
                            "Tell Parkio if you want a parking check before you return.");
            case "smartReturnAvailable" -> locale == NotificationLocale.TR
                    ? new Template(
                            "Park yeri müsait olabilir",
                            "Kayıtlı ev alanınızın yakınında şu an park yeri müsait olabilir.")
                    : new Template(
                            "Parking may be available",
                            "Parking near your saved home area may be available now.");
            case "pointsDeducted" -> locale == NotificationLocale.TR
                    ? new Template("Dikkat", "{points} puan kaybettiniz (ceza).")
                    : new Template("Heads up", "You lost {points} points (penalty).");
            case "trustChanged" -> locale == NotificationLocale.TR
                    ? new Template(
                            "Dikkat",
                            "Güven puanınız {previousScore} değerinden {newScore} değerine {directionLabel}.")
                    : new Template(
                            "Heads up",
                            "Your trust score {directionLabel} from {previousScore} to {newScore}.");
            case "spotRejectedIllegal" -> locale == NotificationLocale.TR
                    ? new Template(
                            "Uyarı",
                            "Park yeriniz yasal olmadığı veya riskli olduğu için reddedildi.")
                    : new Template(
                            "Heads up",
                            "Your parking spot was rejected as illegal or risky.");
            case "accountSuspended" -> locale == NotificationLocale.TR
                    ? new Template("Uyarı", "Hesabınız moderasyon tarafından askıya alındı.")
                    : new Template("Heads up", "Your account has been suspended by moderation.");
            case "spotRejectedByModerator" -> locale == NotificationLocale.TR
                    ? new Template("Uyarı", "Park yeriniz bir moderatör tarafından reddedildi.")
                    : new Template("Heads up", "Your parking spot was rejected by a moderator.");
            case "accountRestored" -> locale == NotificationLocale.TR
                    ? new Template("Güncelleme", "Hesabınız yeniden etkinleştirildi.")
                    : new Template("Update", "Your account has been restored.");
            case "appealResolved" -> locale == NotificationLocale.TR
                    ? new Template("İtiraz güncellemesi", "İtirazınız {outcomeLabel}.")
                    : new Template("Appeal update", "Your appeal was {outcomeLabel}.");
            case "moderationCaseResolved" -> locale == NotificationLocale.TR
                    ? new Template(
                            "Güncelleme",
                            "Hesabınızla ilgili bir moderasyon vakası sonuçlandırıldı.")
                    : new Template(
                            "Update",
                            "A moderation case about your account was resolved.");
            case "nearbyParking" -> locale == NotificationLocale.TR
                    ? new Template("Yakında park yeri", "Yakınınızda yeni bir park yeri paylaşıldı.")
                    : new Template("Nearby parking", "A new parking spot was shared near you.");
            case "parkingSessionReminder1" -> locale == NotificationLocale.TR
                    ? new Template(
                            "Aracınız hâlâ burada mı?",
                            "Park oturumunuz hâlâ aktif. Hâlâ park halinde misiniz?")
                    : new Template(
                            "Is your vehicle still parked here?",
                            "Your parking session is still active. Are you still parked?");
            case "parkingSessionReminder2" -> locale == NotificationLocale.TR
                    ? new Template(
                            "Park oturumu yarın sonlanacak",
                            "Onaylamazsanız park oturumunuz yarın otomatik olarak sonlandırılacak.")
                    : new Template(
                            "We'll end your parking session tomorrow",
                            "We'll automatically end your parking session tomorrow unless you confirm.");
            default -> locale == NotificationLocale.TR
                    ? new Template("Bildirim", "Yeni bir bildiriminiz var.")
                    : new Template("Notification", "You have a new notification.");
        };
    }

    private static String substitute(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private record Template(String title, String body) {
    }
}
