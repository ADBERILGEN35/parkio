package com.parkio.parking.externalsource.izelman;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Maps one official tariff-matrix CSV row to one {@link NormalizedTariffPlan} with multiple bands.
 * Plan identity is the facility/category title; band identity includes duration, vehicle class,
 * fee kind and label — not the plan title alone.
 */
@Component
public class IzelmanTariffMapper {
    private static final Pattern DURATION_HOURS =
            Pattern.compile("^(\\d+)\\s*-\\s*(\\d+)\\s*SAAT", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TIME_OF_DAY = Pattern.compile(
            "^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2}).*?(?:\\((\\d+)\\s*SAAT\\))?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final int ORIGINAL_TEXT_LIMIT = 8192;

    public NormalizedTariffPlan map(Map<String, String> row, Instant sourceContentAt, Instant fetchedAt) {
        String name = planName(row);
        var bands = new ArrayList<NormalizedTariffPlan.RateBand>();
        int order = 0;
        int textualFallbacks = 0;
        for (var entry : row.entrySet()) {
            if (isPlanNameHeader(entry.getKey())) {
                continue;
            }
            String label = entry.getKey() == null ? "" : entry.getKey().trim();
            String rawValue = entry.getValue();
            if (label.isEmpty()) {
                continue;
            }
            if (rawValue == null || rawValue.isBlank()) {
                continue;
            }
            BandDraft draft = parseBand(label, rawValue);
            if (draft == null) {
                continue;
            }
            if (draft.parseStatus() == NormalizedTariffPlan.ParseStatus.TEXTUAL_FALLBACK
                    || draft.parseStatus() == NormalizedTariffPlan.ParseStatus.UNPARSEABLE) {
                textualFallbacks++;
                // Schema requires amount NOT NULL; keep only plan-level original text for ambiguous cells.
                continue;
            }
            bands.add(new NormalizedTariffPlan.RateBand(
                    ++order,
                    draft.fromMinutes(),
                    draft.toMinutes(),
                    draft.amount(),
                    draft.feeKind(),
                    label,
                    draft.vehicleClass(),
                    "ANY",
                    draft.parseStatus()));
        }
        String original = row.toString();
        if (original.length() > ORIGINAL_TEXT_LIMIT) {
            original = original.substring(0, ORIGINAL_TEXT_LIMIT);
        }
        SourceAgeClassification age = SourceAgeClassifier.classify(sourceContentAt, fetchedAt, 180, 730);
        TariffCurrentness currentness = age == SourceAgeClassification.HISTORICAL
                ? TariffCurrentness.HISTORICAL
                : TariffCurrentness.UNKNOWN;
        String id = IzelmanExternalId.of(IzelmanSourceKeys.TARIFFS, name, null, null, null);
        return new NormalizedTariffPlan(
                id,
                name,
                currentness,
                original,
                List.copyOf(bands),
                IzelmanCsvReader.sha256(canonicalRowBytes(row)));
    }

    /** Exposed for dry-run quality reporting. */
    public int countTextualFallbackCells(Map<String, String> row) {
        int count = 0;
        for (var entry : row.entrySet()) {
            if (isPlanNameHeader(entry.getKey())) {
                continue;
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            BandDraft draft = parseBand(entry.getKey(), entry.getValue());
            if (draft != null
                    && (draft.parseStatus() == NormalizedTariffPlan.ParseStatus.TEXTUAL_FALLBACK
                            || draft.parseStatus() == NormalizedTariffPlan.ParseStatus.UNPARSEABLE)) {
                count++;
            }
        }
        return count;
    }

    private static BandDraft parseBand(String label, String rawValue) {
        String header = label.toUpperCase(Locale.ROOT);
        String vehicle = vehicleClass(header);
        NormalizedTariffPlan.FeeKind feeKind = feeKind(header);
        BigDecimal amount = amount(rawValue);
        if (amount == null) {
            return new BandDraft(
                    null,
                    null,
                    null,
                    feeKind,
                    vehicle,
                    NormalizedTariffPlan.ParseStatus.TEXTUAL_FALLBACK);
        }

        Matcher duration = DURATION_HOURS.matcher(header);
        if (duration.find()) {
            return new BandDraft(
                    Integer.parseInt(duration.group(1)) * 60,
                    Integer.parseInt(duration.group(2)) * 60,
                    amount,
                    feeKind == NormalizedTariffPlan.FeeKind.SUBSCRIPTION
                            ? NormalizedTariffPlan.FeeKind.FIXED
                            : feeKind,
                    vehicle,
                    NormalizedTariffPlan.ParseStatus.PARSED);
        }

        Matcher timeOfDay = TIME_OF_DAY.matcher(header);
        if (timeOfDay.find()) {
            Integer span = timeOfDay.group(5) == null ? null : Integer.parseInt(timeOfDay.group(5)) * 60;
            return new BandDraft(
                    0,
                    span,
                    amount,
                    NormalizedTariffPlan.FeeKind.FIXED,
                    vehicle,
                    NormalizedTariffPlan.ParseStatus.PARSED);
        }

        if (feeKind == NormalizedTariffPlan.FeeKind.SUBSCRIPTION
                || feeKind == NormalizedTariffPlan.FeeKind.OTHER) {
            return new BandDraft(
                    null, null, amount, feeKind, vehicle, NormalizedTariffPlan.ParseStatus.PARSED);
        }

        // Non-empty monetary cell under an unrecognized header — keep as OTHER band.
        return new BandDraft(
                null, null, amount, NormalizedTariffPlan.FeeKind.OTHER, vehicle,
                NormalizedTariffPlan.ParseStatus.PARSED);
    }

    private static NormalizedTariffPlan.FeeKind feeKind(String headerUpper) {
        if (headerUpper.contains("ABONE")) {
            return NormalizedTariffPlan.FeeKind.SUBSCRIPTION;
        }
        if (headerUpper.contains("KAYIP") || headerUpper.contains("BILET")) {
            return NormalizedTariffPlan.FeeKind.OTHER;
        }
        return NormalizedTariffPlan.FeeKind.FIXED;
    }

    private static String vehicleClass(String headerUpper) {
        if (headerUpper.contains("MOTOSIKLET") || headerUpper.contains("MOTORSIKLET")) {
            return "MOTORCYCLE";
        }
        if (headerUpper.contains("ENGELLI")) {
            return "DISABLED";
        }
        return "CAR";
    }

    private static String planName(Map<String, String> row) {
        String name = first(
                row,
                "OTOPARK / FIYAT",
                "OTOPARK/FIYAT",
                "OTOPARK_ADI",
                "OTOPARK",
                "ADI",
                "TARIFE_ADI",
                "PLAN_ADI");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tariff plan name is required");
        }
        return name.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static boolean isPlanNameHeader(String header) {
        if (header == null) {
            return false;
        }
        String h = header.trim().toUpperCase(Locale.ROOT);
        return h.equals("OTOPARK / FIYAT")
                || h.equals("OTOPARK/FIYAT")
                || h.equals("OTOPARK_ADI")
                || h.equals("OTOPARK")
                || h.equals("ADI")
                || h.equals("TARIFE_ADI")
                || h.equals("PLAN_ADI")
                || h.equals("ILCE");
    }

    private static BigDecimal amount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.replace("\u20BA", "")
                .replace("TL", "")
                .replace(" ", "")
                .trim()
                .replace(',', '.');
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String first(Map<String, String> row, String... names) {
        for (String name : names) {
            String value = row.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static byte[] canonicalRowBytes(Map<String, String> row) {
        StringBuilder sb = new StringBuilder();
        for (var entry : row.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private record BandDraft(
            Integer fromMinutes,
            Integer toMinutes,
            BigDecimal amount,
            NormalizedTariffPlan.FeeKind feeKind,
            String vehicleClass,
            NormalizedTariffPlan.ParseStatus parseStatus) {}
}
