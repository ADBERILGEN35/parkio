package com.parkio.parking.application.quality;

import java.util.Map;

/**
 * Allow-listed projection of a persisted OSM import {@code quality_report_json}.
 * Unknown keys, oversized maps and unknown label outcomes are dropped.
 */
public record NormalizedQualityReport(
        boolean present,
        Long named,
        Long unnamed,
        Long capacityKnown,
        String clipVersion,
        String labelPolicyVersion,
        Map<String, Long> rejectReasons,
        Map<String, Long> labelOutcomes) {

    public static NormalizedQualityReport empty() {
        return new NormalizedQualityReport(false, null, null, null, null, null, Map.of(), Map.of());
    }
}
