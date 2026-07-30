package com.parkio.parking.externalsource.izelman;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class IzelmanTariffMapper {
    private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)\\s*(?i:saat)");
    private static final int ORIGINAL_TEXT_LIMIT = 8192;

    public NormalizedTariffPlan map(Map<String, String> row, Instant sourceContentAt, Instant fetchedAt) {
        String name = first(row, "OTOPARK_ADI", "OTOPARK", "ADI", "TARIFE_ADI");
        if (name == null) name = "İZELMAN parking tariff";
        var bands = new ArrayList<NormalizedTariffPlan.RateBand>();
        int order = 0;
        for (var entry : row.entrySet()) {
            Matcher matcher = HOURS.matcher(entry.getKey());
            if (!matcher.find()) continue;
            BigDecimal amount = amount(entry.getValue());
            if (amount == null) continue;
            bands.add(new NormalizedTariffPlan.RateBand(++order,
                    Integer.parseInt(matcher.group(1)) * 60,
                    Integer.parseInt(matcher.group(2)) * 60,
                    amount, NormalizedTariffPlan.FeeKind.FIXED, entry.getKey()));
        }
        String original = row.toString();
        if (original.length() > ORIGINAL_TEXT_LIMIT) original = original.substring(0, ORIGINAL_TEXT_LIMIT);
        SourceAgeClassification age = SourceAgeClassifier.classify(sourceContentAt, fetchedAt, 180, 730);
        TariffCurrentness currentness = age == SourceAgeClassification.HISTORICAL
                ? TariffCurrentness.HISTORICAL : TariffCurrentness.UNKNOWN;
        String id = IzelmanExternalId.of(IzelmanSourceKeys.TARIFFS, name, null, null, row.get("ILCE"));
        return new NormalizedTariffPlan(id, name, currentness, original, java.util.List.copyOf(bands),
                IzelmanCsvReader.sha256(row.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static BigDecimal amount(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.replace("₺", "").replace("TL", "").trim().replace(',', '.');
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String first(Map<String, String> row, String... names) {
        for (String name : names) {
            String value = row.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
