package com.parkio.parking.domain;

import java.util.Locale;

/**
 * Short, admin-safe rejection messages. Turkish is the primary locale; English is the
 * fallback. Never expose raw provider prompts or stack traces.
 */
public final class RejectionReasonCatalog {

    private RejectionReasonCatalog() {
    }

    public static String messageTr(RejectionReasonCode code) {
        return switch (code) {
            case LEGACY_POLICY_RESET ->
                    "Eski doğrulama politikası kapsamında oluşturulan kayıt, yeni politika geçişi nedeniyle kapatıldı.";
            case CLEARLY_UNRELATED_CONTENT ->
                    "Fotoğraf park yeri veya yol bağlamıyla ilgili görünmüyor.";
            case INDOOR_SCENE ->
                    "Fotoğraf bir iç mekân görüntüsü olduğu için reddedildi.";
            case SELFIE_OR_PERSONAL_PHOTO ->
                    "Fotoğraf park alanı yerine kişisel bir görüntü içeriyor.";
            case FOOD_OR_RANDOM_OBJECT ->
                    "Fotoğraf park yeri yerine yiyecek veya rastgele bir nesne içeriyor.";
            case SCREENSHOT_OR_DOCUMENT ->
                    "Fotoğraf ekran görüntüsü veya belge olduğu için reddedildi.";
            case NO_ROAD_OR_PARKING_CONTEXT ->
                    "Görüntüde yol veya park bağlamı tespit edilemedi.";
            case UNUSABLE_IMAGE ->
                    "Görüntü doğrulama için yeterince kullanılabilir değil.";
            case IMAGE_TOO_DARK ->
                    "Fotoğraf aşırı karanlık olduğu için değerlendirilemedi.";
            case IMAGE_TOO_BLURRY ->
                    "Fotoğraf aşırı bulanık olduğu için değerlendirilemedi.";
            case IMAGE_CORRUPTED ->
                    "Görüntü bozuk veya okunamaz olduğu için reddedildi.";
            case LEGALITY_CONCERN ->
                    "Görüntü yasal/erişim kaygısı nedeniyle reddedildi.";
            case DUPLICATE_SUBMISSION ->
                    "Kayıt yinelenen bir gönderim olduğu için reddedildi.";
            case MANUAL_MODERATOR_REJECTION ->
                    "Kayıt bir moderatör tarafından reddedildi.";
            case OTHER ->
                    "Kayıt doğrulama politikası kapsamında reddedildi.";
        };
    }

    public static String messageEn(RejectionReasonCode code) {
        return switch (code) {
            case LEGACY_POLICY_RESET ->
                    "Submission closed during migration to the new validation policy.";
            case CLEARLY_UNRELATED_CONTENT ->
                    "The photo does not appear related to a parking space or road context.";
            case INDOOR_SCENE ->
                    "The photo was rejected because it shows an indoor scene.";
            case SELFIE_OR_PERSONAL_PHOTO ->
                    "The photo contains a personal image rather than a parking space.";
            case FOOD_OR_RANDOM_OBJECT ->
                    "The photo shows food or an unrelated object rather than parking.";
            case SCREENSHOT_OR_DOCUMENT ->
                    "The photo was rejected because it is a screenshot or document.";
            case NO_ROAD_OR_PARKING_CONTEXT ->
                    "No road or parking context could be identified in the image.";
            case UNUSABLE_IMAGE ->
                    "The image is not usable enough for validation.";
            case IMAGE_TOO_DARK ->
                    "The photo is too dark to evaluate.";
            case IMAGE_TOO_BLURRY ->
                    "The photo is too blurry to evaluate.";
            case IMAGE_CORRUPTED ->
                    "The image is corrupted or unreadable.";
            case LEGALITY_CONCERN ->
                    "The submission was rejected due to a legality or access concern.";
            case DUPLICATE_SUBMISSION ->
                    "The submission was rejected as a duplicate.";
            case MANUAL_MODERATOR_REJECTION ->
                    "The submission was rejected by a moderator.";
            case OTHER ->
                    "The submission was rejected under the validation policy.";
        };
    }

    public static String message(RejectionReasonCode code, Locale locale) {
        if (locale != null && "en".equalsIgnoreCase(locale.getLanguage())) {
            return messageEn(code);
        }
        return messageTr(code);
    }

    /**
     * Maps a vision / product wire reason into a controlled {@link RejectionReasonCode}.
     * Unknown values fall back to {@link RejectionReasonCode#OTHER}.
     */
    public static RejectionReasonCode fromWireReason(String wireReason) {
        if (wireReason == null || wireReason.isBlank()) {
            return RejectionReasonCode.OTHER;
        }
        String normalized = wireReason.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LEGACY_POLICY_RESET" -> RejectionReasonCode.LEGACY_POLICY_RESET;
            case "CLEARLY_UNRELATED_CONTENT", "UNRELATED_SUBJECT" -> RejectionReasonCode.CLEARLY_UNRELATED_CONTENT;
            case "INDOOR_SCENE" -> RejectionReasonCode.INDOOR_SCENE;
            case "SELFIE_OR_PERSONAL_PHOTO" -> RejectionReasonCode.SELFIE_OR_PERSONAL_PHOTO;
            case "FOOD_OR_RANDOM_OBJECT" -> RejectionReasonCode.FOOD_OR_RANDOM_OBJECT;
            case "SCREENSHOT_OR_DOCUMENT", "SCREENSHOT_OR_SYNTHETIC" -> RejectionReasonCode.SCREENSHOT_OR_DOCUMENT;
            case "NO_ROAD_OR_PARKING_CONTEXT" -> RejectionReasonCode.NO_ROAD_OR_PARKING_CONTEXT;
            case "UNUSABLE_IMAGE", "TOO_DARK_OR_BLURRY" -> RejectionReasonCode.UNUSABLE_IMAGE;
            case "IMAGE_TOO_DARK" -> RejectionReasonCode.IMAGE_TOO_DARK;
            case "IMAGE_TOO_BLURRY" -> RejectionReasonCode.IMAGE_TOO_BLURRY;
            case "IMAGE_CORRUPTED" -> RejectionReasonCode.IMAGE_CORRUPTED;
            case "LEGALITY_CONCERN", "LEGALITY_UNCERTAIN", "CLEARLY_RESTRICTED_AREA" ->
                    RejectionReasonCode.LEGALITY_CONCERN;
            case "DUPLICATE_SUBMISSION" -> RejectionReasonCode.DUPLICATE_SUBMISSION;
            case "MANUAL_MODERATOR_REJECTION" -> RejectionReasonCode.MANUAL_MODERATOR_REJECTION;
            default -> {
                try {
                    yield RejectionReasonCode.valueOf(normalized);
                } catch (IllegalArgumentException ignored) {
                    yield RejectionReasonCode.OTHER;
                }
            }
        };
    }
}
