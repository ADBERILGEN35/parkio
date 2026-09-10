package com.parkio.parking.externalsource.izelman;

import java.math.BigDecimal;
import java.util.List;

public record NormalizedTariffPlan(
        String externalId, String planName, TariffCurrentness currentness, String originalText,
        List<RateBand> bands, String rawRecordHash) {

    public record RateBand(
            int order,
            Integer durationFromMinutes,
            Integer durationToMinutes,
            BigDecimal amount,
            FeeKind feeKind,
            String label,
            String vehicleClass,
            String dayCategory,
            ParseStatus parseStatus) {}

    public enum FeeKind { FIXED, INCREMENTAL, SUBSCRIPTION, OTHER }

    public enum ParseStatus { PARSED, TEXTUAL_FALLBACK, UNPARSEABLE }
}