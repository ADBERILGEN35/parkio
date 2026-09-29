package com.parkio.parking.infrastructure.konya;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Aggregates bay/peron rows into canonical parking zones keyed by normalized {@code bolgeadi}.
 */
@Component
public class KonyaZoneAggregator {
    private final KonyaCoordinateParser coordinateParser;
    private final KonyaGeoValidator geoValidator;

    public KonyaZoneAggregator(KonyaCoordinateParser coordinateParser, KonyaGeoValidator geoValidator) {
        this.coordinateParser = coordinateParser;
        this.geoValidator = geoValidator;
    }

    public AggregationResult aggregate(List<KonyaParkingRecordDto> records) {
        Map<String, ZoneBuilder> zones = new LinkedHashMap<>();
        int invalidCoordinateRows = 0;
        int validCoordinateRows = 0;

        for (KonyaParkingRecordDto record : records) {
            if (record == null) {
                continue;
            }
            String zoneKey = normalizeZoneName(record.bolgeadi());
            if (zoneKey == null) {
                continue;
            }
            ZoneBuilder builder = zones.computeIfAbsent(zoneKey, k -> new ZoneBuilder(record.bolgeadi()));
            builder.addRecord(record);

            List<KonyaCoordinateParser.KonyaCoordinatePoint> points =
                    coordinateParser.parsePoints(record.peronkoordinat());
            if (points.isEmpty()) {
                invalidCoordinateRows++;
                continue;
            }
            boolean rowHasValid = false;
            for (KonyaCoordinateParser.KonyaCoordinatePoint point : points) {
                if (geoValidator.isValidCoordinate(point.latitude(), point.longitude())) {
                    builder.validPoints.add(point);
                    rowHasValid = true;
                }
            }
            if (rowHasValid) {
                validCoordinateRows++;
            } else {
                invalidCoordinateRows++;
            }
        }

        List<AggregatedZone> aggregated = new ArrayList<>();
        int unmappableZones = 0;
        for (ZoneBuilder builder : zones.values()) {
            AggregatedZone zone = builder.build();
            if (zone == null) {
                unmappableZones++;
            } else {
                aggregated.add(zone);
            }
        }
        return new AggregationResult(
                List.copyOf(aggregated), zones.size(), unmappableZones, validCoordinateRows, invalidCoordinateRows);
    }

    static String normalizeZoneName(String zoneName) {
        if (zoneName == null || zoneName.isBlank()) {
            return null;
        }
        String trimmed = zoneName.trim().replaceAll("\\s+", " ");
        String folded = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
                .toUpperCase(Locale.forLanguageTag("tr-TR"));
        return folded.isBlank() ? null : folded;
    }

    static String externalIdForZone(String normalizedZoneKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("konya-bolge:" + normalizedZoneKey).getBytes(StandardCharsets.UTF_8));
            return "konya-zone-" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record AggregationResult(
            List<AggregatedZone> zones,
            int logicalZoneCount,
            int unmappableZones,
            int validCoordinateRows,
            int invalidCoordinateRows) {}

    public record AggregatedZone(
            String externalId,
            String displayName,
            String addressText,
            Integer capacityTotal,
            String openingHours,
            double latitude,
            double longitude,
            int sourceRowCount,
            int validCoordinateCount) {}

    private final class ZoneBuilder {
        private final String displayName;
        private final Set<Integer> zoneCapacities = new LinkedHashSet<>();
        private String addressText;
        private String openingHours;
        private int sourceRowCount;
        private final Set<KonyaCoordinateParser.KonyaCoordinatePoint> validPoints = new LinkedHashSet<>();

        private ZoneBuilder(String displayName) {
            this.displayName = displayName == null ? null : displayName.trim().replaceAll("\\s+", " ");
        }

        private void addRecord(KonyaParkingRecordDto record) {
            sourceRowCount++;
            if (record.bolgekapasite() != null && record.bolgekapasite() > 0) {
                zoneCapacities.add(record.bolgekapasite());
            }
            if (addressText == null && record.bolgeadresi() != null && !record.bolgeadresi().isBlank()) {
                addressText = record.bolgeadresi().trim();
            }
            openingHours = preferOpeningHours(openingHours, formatHours(record));
        }

        private AggregatedZone build() {
            if (displayName == null || displayName.isBlank() || validPoints.isEmpty()) {
                return null;
            }
            String zoneKey = normalizeZoneName(displayName);
            if (zoneKey == null) {
                return null;
            }
            double latSum = 0;
            double lngSum = 0;
            for (KonyaCoordinateParser.KonyaCoordinatePoint point : validPoints) {
                latSum += point.latitude();
                lngSum += point.longitude();
            }
            int count = validPoints.size();
            return new AggregatedZone(
                    externalIdForZone(zoneKey),
                    displayName,
                    addressText,
                    resolveCapacity(zoneCapacities),
                    openingHours,
                    latSum / count,
                    lngSum / count,
                    sourceRowCount,
                    count);
        }
    }

    static Integer resolveCapacity(Set<Integer> zoneCapacities) {
        if (zoneCapacities.isEmpty()) {
            return null;
        }
        if (zoneCapacities.size() == 1) {
            return zoneCapacities.iterator().next();
        }
        // Conflicting zone totals — prefer the maximum consistent publisher value.
        return zoneCapacities.stream().max(Integer::compareTo).orElse(null);
    }

    static String formatHours(KonyaParkingRecordDto record) {
        if (record.peronacilissaati() == null && record.peronkapanissaati() == null) {
            return null;
        }
        String open = hhmm(record.peronacilissaati());
        String close = hhmm(record.peronkapanissaati());
        if (open == null && close == null) {
            return null;
        }
        return (open == null ? "?" : open) + "-" + (close == null ? "?" : close);
    }

    static String preferOpeningHours(String current, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank()) {
            return candidate;
        }
        // Prefer 24h-style schedules when present.
        if (candidate.contains("00:00") && candidate.contains("24:00")) {
            return candidate;
        }
        return current;
    }

    static String hhmm(Integer value) {
        if (value == null || value < 0) {
            return null;
        }
        int hours = value / 100;
        int minutes = value % 100;
        if (hours == 24 && minutes == 0) {
            return "24:00";
        }
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
            return null;
        }
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }
}
