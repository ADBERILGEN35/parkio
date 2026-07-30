package com.parkio.parking.externalsource.osm;

import java.text.Normalizer;
import java.util.Locale;

public final class OsmNameNormalizer {
    private OsmNameNormalizer() {}

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String nfd = Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        String stripped = nfd.replaceAll("\\p{M}+", "");
        return stripped.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    public static double similarity(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        if (left.equals(right)) {
            return 1.0;
        }
        int max = Math.max(left.length(), right.length());
        int distance = levenshtein(left, right);
        return 1.0 - ((double) distance / max);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}