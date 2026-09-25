package com.parkio.parking.externalsource.osm;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * DATA-WP-13 — centralized OSM public display-label policy.
 *
 * <p>Single source of truth for import normalization. Do not duplicate in parser/DTO layers.
 */
public final class OsmDisplayLabelPolicy {
    public static final String POLICY_V1 = "osm-label-v1";
    public static final String POLICY_LEGACY = "legacy";
    public static final int MAX_PUBLIC_LABEL_LENGTH = 120;

    private static final Pattern OSM_ID =
            Pattern.compile("(?i)^(node|way|relation)\\s*/\\s*\\d+$");
    private static final Pattern OSM_TECHNICAL_PREFIX =
            Pattern.compile("(?i)^osm\\s+parking\\s+(node|way|relation)\\s*/\\s*\\d+$");
    private static final Pattern URL =
            Pattern.compile("(?i)(https?://|www\\.)");
    private static final Pattern PHONE =
            Pattern.compile("(?i)^(\\+|00)?[0-9][0-9\\s().-]{6,}[0-9]$");
    private static final Pattern COORD =
            Pattern.compile("^\\s*-?\\d+(\\.\\d+)?\\s*,\\s*-?\\d+(\\.\\d+)?\\s*$");
    private static final Pattern CONTROL =
            Pattern.compile("[\\p{Cntrl}&&[^\\t]]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private OsmDisplayLabelPolicy() {}

    public static boolean isKnownPolicy(String policy) {
        if (policy == null || policy.isBlank()) {
            return false;
        }
        String p = policy.trim().toLowerCase(Locale.ROOT);
        return POLICY_V1.equals(p) || POLICY_LEGACY.equals(p);
    }

    public static String normalizePolicyVersion(String configured) {
        if (configured == null || configured.isBlank()) {
            return POLICY_V1;
        }
        String p = configured.trim().toLowerCase(Locale.ROOT);
        if (POLICY_LEGACY.equals(p)) {
            return POLICY_LEGACY;
        }
        return POLICY_V1;
    }

    public static OsmDisplayLabelSelection select(
            String policyVersion,
            String externalId,
            Map<String, String> tags) {
        Objects.requireNonNull(externalId, "externalId");
        String version = normalizePolicyVersion(policyVersion);
        Map<String, String> safeTags = tags == null ? Map.of() : tags;
        if (POLICY_LEGACY.equals(version)) {
            return selectLegacy(externalId, safeTags);
        }
        return selectV1(externalId, safeTags);
    }

    private static OsmDisplayLabelSelection selectLegacy(String externalId, Map<String, String> tags) {
        ValidationStats stats = new ValidationStats();
        String name = validatedCandidate(tags.get("name"), externalId, stats);
        if (name != null) {
            return new OsmDisplayLabelSelection(
                    name, OsmDisplayLabelOutcome.REAL_NAME_SELECTED, POLICY_LEGACY,
                    stats.invalidRejected, stats.technicalIdRejected);
        }
        String technical = "OSM parking " + externalId;
        return new OsmDisplayLabelSelection(
                technical, OsmDisplayLabelOutcome.LEGACY_TECHNICAL, POLICY_LEGACY,
                stats.invalidRejected, stats.technicalIdRejected);
    }

    private static OsmDisplayLabelSelection selectV1(String externalId, Map<String, String> tags) {
        ValidationStats stats = new ValidationStats();

        String nameTr = validatedCandidate(tags.get("name:tr"), externalId, stats);
        if (nameTr != null) {
            return selection(nameTr, OsmDisplayLabelOutcome.LOCALIZED_NAME_SELECTED, stats);
        }
        String name = validatedCandidate(tags.get("name"), externalId, stats);
        if (name != null) {
            return selection(name, OsmDisplayLabelOutcome.REAL_NAME_SELECTED, stats);
        }
        String official = validatedCandidate(tags.get("official_name"), externalId, stats);
        if (official != null) {
            return selection(official, OsmDisplayLabelOutcome.REAL_NAME_SELECTED, stats);
        }
        String shortName = validatedCandidate(tags.get("short_name"), externalId, stats);
        if (shortName != null) {
            return selection(shortName, OsmDisplayLabelOutcome.REAL_NAME_SELECTED, stats);
        }

        String operator = validatedCandidate(tags.get("operator"), externalId, stats);
        if (operator != null) {
            String label = composePossessiveParking(operator);
            if (label != null) {
                return selection(label, OsmDisplayLabelOutcome.OPERATOR_FALLBACK, stats);
            }
        }
        String brand = validatedCandidate(tags.get("brand"), externalId, stats);
        if (brand != null) {
            String label = composePossessiveParking(brand);
            if (label != null) {
                return selection(label, OsmDisplayLabelOutcome.BRAND_FALLBACK, stats);
            }
        }

        String typeLabel = typeAwareFallback(tags);
        if (typeLabel != null) {
            return selection(typeLabel, OsmDisplayLabelOutcome.TYPE_FALLBACK, stats);
        }
        return selection("Otopark", OsmDisplayLabelOutcome.NEUTRAL_FALLBACK, stats);
    }

    private static OsmDisplayLabelSelection selection(
            String label, OsmDisplayLabelOutcome outcome, ValidationStats stats) {
        return new OsmDisplayLabelSelection(
                label, outcome, POLICY_V1, stats.invalidRejected, stats.technicalIdRejected);
    }

    /**
     * Returns a display-safe candidate or null when rejected.
     * Updates stats for invalid / technical-id rejections.
     */
    static String validatedCandidate(String raw, String externalId, ValidationStats stats) {
        if (raw == null) {
            return null;
        }
        String normalized = normalizeDisplayText(raw);
        if (normalized == null) {
            stats.invalidRejected++;
            return null;
        }
        if (isTechnicalIdentifier(normalized, externalId)) {
            stats.technicalIdRejected++;
            stats.invalidRejected++;
            return null;
        }
        if (!isAcceptableNameLiteral(normalized)) {
            stats.invalidRejected++;
            return null;
        }
        return normalized;
    }

    static String normalizeDisplayText(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = MULTI_SPACE.matcher(raw.trim()).replaceAll(" ");
        if (trimmed.isEmpty()) {
            return null;
        }
        if (CONTROL.matcher(trimmed).find()) {
            return null;
        }
        if (trimmed.length() > MAX_PUBLIC_LABEL_LENGTH) {
            return null;
        }
        return trimmed;
    }

    static boolean isTechnicalIdentifier(String value, String externalId) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (externalId != null && value.equalsIgnoreCase(externalId)) {
            return true;
        }
        if (OSM_ID.matcher(value).matches() || OSM_TECHNICAL_PREFIX.matcher(value).matches()) {
            return true;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("osm parking node/")
                || lower.startsWith("osm parking way/")
                || lower.startsWith("osm parking relation/");
    }

    static boolean isAcceptableNameLiteral(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("yes")
                || lower.equals("no")
                || lower.equals("true")
                || lower.equals("false")
                || lower.equals("unknown")
                || lower.equals("unnamed")
                || lower.equals("null")
                || lower.equals("n/a")
                || lower.equals("na")
                || lower.equals("-")
                || lower.equals("none")
                || lower.equals("parking")
                || lower.equals("otopark")) {
            return false;
        }
        if (URL.matcher(value).find()) {
            return false;
        }
        if (PHONE.matcher(value).matches()) {
            return false;
        }
        if (COORD.matcher(value).matches()) {
            return false;
        }
        return true;
    }

    static String composePossessiveParking(String base) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.contains("otopark")) {
            // Avoid "Otopark Otoparkı" / "X Otopark Otoparkı"
            if (lower.equals("otopark") || lower.equals("otoparkı") || lower.equals("otoparki")) {
                return null;
            }
            return truncate(base);
        }
        String composed = base + " Otoparkı";
        if (composed.toLowerCase(Locale.ROOT).contains("otopark otopark")) {
            return null;
        }
        return truncate(composed);
    }

    static String typeAwareFallback(Map<String, String> tags) {
        String parking = tagLower(tags, "parking");
        String covered = tagLower(tags, "covered");
        String underground = tagLower(tags, "underground");
        String parkRide = tagLower(tags, "park_ride");
        String building = tagLower(tags, "building");

        if (isYes(parkRide)) {
            return "Park Et ve Devam Et Otoparkı";
        }
        if (isYes(underground) || parking.equals("underground")) {
            return "Yer Altı Otoparkı";
        }
        if (parking.equals("multi-storey") || parking.equals("multi_storey") || parking.equals("multistorey")) {
            return "Katlı Otopark";
        }
        if (isYes(covered) || building.equals("yes") || building.equals("garage") || parking.equals("garage")) {
            return "Kapalı Otopark";
        }
        if (covered.equals("no") || parking.equals("surface") || parking.equals("street_side")) {
            return "Açık Otopark";
        }
        return null;
    }

    private static String tagLower(Map<String, String> tags, String key) {
        String value = tags.get(key);
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isYes(String value) {
        return "yes".equals(value) || "true".equals(value) || "1".equals(value);
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_PUBLIC_LABEL_LENGTH) {
            return value;
        }
        return null;
    }

    static final class ValidationStats {
        int invalidRejected;
        int technicalIdRejected;
    }
}
