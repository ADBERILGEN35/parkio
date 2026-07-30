package com.parkio.parking.externalsource.osm;

public final class OsmCapacityParser {
    private OsmCapacityParser() {}

    public static Integer parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim();
        int end = 0;
        while (end < cleaned.length() && Character.isDigit(cleaned.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        try {
            int value = Integer.parseInt(cleaned.substring(0, end));
            return value >= 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}