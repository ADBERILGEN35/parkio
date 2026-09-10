package com.parkio.parking.externalsource.izelman;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

public final class IzelmanExternalId {
    private IzelmanExternalId() {}

    public static String of(String datasetKey, String name, Double latitude, Double longitude, String district) {
        return IzelmanCsvReader.sha256(String.join("|",
                normalized(datasetKey), normalized(name), coordinate(latitude), coordinate(longitude),
                normalized(district)).getBytes(StandardCharsets.UTF_8));
    }

    private static String coordinate(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.6f", value);
    }

    private static String normalized(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").toLowerCase(Locale.forLanguageTag("tr"));
    }
}
