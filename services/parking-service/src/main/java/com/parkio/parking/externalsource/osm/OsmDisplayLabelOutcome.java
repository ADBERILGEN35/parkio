package com.parkio.parking.externalsource.osm;

/** Bounded label-selection outcome for metrics and quality reports (DATA-WP-13). */
public enum OsmDisplayLabelOutcome {
    LOCALIZED_NAME_SELECTED("localized_name_selected", "localized_name", true),
    REAL_NAME_SELECTED("real_name_selected", "real_name", true),
    OPERATOR_FALLBACK("operator_fallback", "operator", false),
    BRAND_FALLBACK("brand_fallback", "brand", false),
    TYPE_FALLBACK("type_fallback", "type", false),
    NEUTRAL_FALLBACK("neutral_fallback", "neutral", false),
    LEGACY_TECHNICAL("legacy_technical", "legacy", false),
    INVALID_NAME_REJECTED("invalid_name_rejected", "invalid", false),
    TECHNICAL_ID_REMOVED("technical_id_removed", "technical_id", false),
    UNCHANGED("unchanged", "unchanged", false);

    private final String metricOutcome;
    private final String fallbackType;
    private final boolean nameBearing;

    OsmDisplayLabelOutcome(String metricOutcome, String fallbackType, boolean nameBearing) {
        this.metricOutcome = metricOutcome;
        this.fallbackType = fallbackType;
        this.nameBearing = nameBearing;
    }

    public String metricOutcome() {
        return metricOutcome;
    }

    public String fallbackType() {
        return fallbackType;
    }

    public boolean nameBearing() {
        return nameBearing;
    }
}
