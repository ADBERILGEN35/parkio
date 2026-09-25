package com.parkio.parking.externalsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.provider.ParkingProviderCatalog;
import java.util.Locale;
import java.util.Set;

/**
 * Query-time İSPARK open-status gate for occupancy publication.
 *
 * <p>{@code isOpen} is stored on {@code municipal_facility_source_links.source_metadata_json} by
 * {@code IsparkNormalizer} and is not applied when occupancy snapshots are written. Publication
 * therefore reads the already-stored metadata so existing closed rows are protected after deploy
 * without a re-sync.
 *
 * <p>Conservative contract, consistent with existing LIVE/AGING-only space publication:
 * <ul>
 *   <li>Explicit closed ({@code 0}, {@code false}, {@code "0"}, {@code "false"}) → do not
 *       publish occupancy ({@link MunicipalOccupancyFreshness#UNAVAILABLE}, omit spaces).</li>
 *   <li>Explicit open ({@code 1}, {@code true}, {@code "1"}, {@code "true"}) → preserve occupancy,
 *       including zero available spaces.</li>
 *   <li>Missing, null, blank, malformed, or unrecognized {@code isOpen} → not treated as open;
 *       occupancy is withheld. The facility stays discoverable.</li>
 * </ul>
 *
 * <p>Closure is never inferred from {@code workHours} or other metadata text. İZUM, OSM, and
 * other municipal sources are a no-op. When another live-occupancy authority is also linked,
 * İSPARK occupancy must still be withheld if open-status is not explicitly OPEN; only that
 * other source's snapshot may be published.
 */
public final class IsparkOccupancyPublicationPolicy {
    public enum OpenStatus {
        OPEN,
        CLOSED,
        UNKNOWN
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IsparkOccupancyPublicationPolicy() {}

    /**
     * Public Explore: suppress when the publishing source is İSPARK and open-status is not
     * explicitly OPEN.
     */
    public static boolean suppressOccupancy(String occupancySourceKey, String isparkSourceMetadataJson) {
        if (!MunicipalSourceIdentity.isIspark(occupancySourceKey)) {
            return false;
        }
        return classifyStoredMetadata(isparkSourceMetadataJson) != OpenStatus.OPEN;
    }

    /**
     * True when an İSPARK link is present and open-status is not explicitly OPEN.
     * İSPARK occupancy must then be withheld even if another live-occupancy source
     * is also linked. Callers may still publish that other source's snapshot.
     */
    public static boolean mustWithholdIsparkOccupancy(
            Set<String> linkedSourceKeys, String isparkSourceMetadataJson) {
        Set<String> keys = MunicipalSourceIdentity.normalizeKeys(linkedSourceKeys);
        if (keys.stream().noneMatch(MunicipalSourceIdentity::isIspark)) {
            return false;
        }
        return classifyStoredMetadata(isparkSourceMetadataJson) != OpenStatus.OPEN;
    }

    /**
     * Authenticated municipal projection: suppress the selected snapshot when İSPARK is the
     * sole live-occupancy authority among linked keys and open-status is not explicitly OPEN.
     */
    public static boolean suppressOccupancyForLinkedSources(
            Set<String> linkedSourceKeys, String isparkSourceMetadataJson) {
        Set<String> keys = MunicipalSourceIdentity.normalizeKeys(linkedSourceKeys);
        if (keys.stream().noneMatch(MunicipalSourceIdentity::isIspark)) {
            return false;
        }
        boolean otherLiveAuthority = keys.stream()
                .filter(key -> !MunicipalSourceIdentity.isIspark(key))
                .anyMatch(ParkingProviderCatalog::supportsLiveOccupancy);
        if (otherLiveAuthority) {
            return false;
        }
        return mustWithholdIsparkOccupancy(keys, isparkSourceMetadataJson);
    }

    public static OpenStatus classifyStoredMetadata(String sourceMetadataJson) {
        if (sourceMetadataJson == null || sourceMetadataJson.isBlank()) {
            return OpenStatus.UNKNOWN;
        }
        try {
            JsonNode root = MAPPER.readTree(sourceMetadataJson);
            if (root == null || !root.isObject()) {
                return OpenStatus.UNKNOWN;
            }
            if (!root.has("isOpen")) {
                return OpenStatus.UNKNOWN;
            }
            return classifyOpenStatus(root.get("isOpen"));
        } catch (Exception ignored) {
            return OpenStatus.UNKNOWN;
        }
    }

    public static OpenStatus classifyOpenStatus(Object raw) {
        if (raw == null) {
            return OpenStatus.UNKNOWN;
        }
        if (raw instanceof JsonNode node) {
            return classifyNode(node);
        }
        if (raw instanceof Boolean value) {
            return value ? OpenStatus.OPEN : OpenStatus.CLOSED;
        }
        if (raw instanceof Number number) {
            return classifyNumber(number.doubleValue());
        }
        if (raw instanceof String text) {
            return classifyText(text);
        }
        return OpenStatus.UNKNOWN;
    }

    private static OpenStatus classifyNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return OpenStatus.UNKNOWN;
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? OpenStatus.OPEN : OpenStatus.CLOSED;
        }
        if (node.isNumber()) {
            return classifyNumber(node.doubleValue());
        }
        if (node.isTextual()) {
            return classifyText(node.asText());
        }
        return OpenStatus.UNKNOWN;
    }

    private static OpenStatus classifyNumber(double value) {
        if (value == 0.0d) {
            return OpenStatus.CLOSED;
        }
        if (value == 1.0d) {
            return OpenStatus.OPEN;
        }
        return OpenStatus.UNKNOWN;
    }

    private static OpenStatus classifyText(String raw) {
        if (raw == null) {
            return OpenStatus.UNKNOWN;
        }
        String folded = raw.trim().toLowerCase(Locale.ROOT);
        if (folded.isEmpty()) {
            return OpenStatus.UNKNOWN;
        }
        if ("0".equals(folded) || "false".equals(folded)) {
            return OpenStatus.CLOSED;
        }
        if ("1".equals(folded) || "true".equals(folded)) {
            return OpenStatus.OPEN;
        }
        return OpenStatus.UNKNOWN;
    }
}
