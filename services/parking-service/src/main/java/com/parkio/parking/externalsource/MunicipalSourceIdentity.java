package com.parkio.parking.externalsource;

import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.infrastructure.anpark.AnparkMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.ispark.IsparkMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.konya.KonyaMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.osm.OsmGeofabrikSourceKeys;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stable municipal source identity helpers.
 * Publication and occupancy decisions must use source keys (or family derived from keys),
 * never attribution / publisher / display-label text.
 */
public final class MunicipalSourceIdentity {
    public static final String FAMILY_IZUM = "izum";
    public static final String FAMILY_ISPARK = "ispark";
    public static final String FAMILY_ANPARK = "anpark";
    public static final String FAMILY_KONYA = "konya";
    public static final String FAMILY_OSM = "osm";
    public static final String FAMILY_IZELMAN = "izelman";
    /** Test-only family; never production-published by default. */
    public static final String FAMILY_FAKE_TEST = "fake_test";
    public static final String FAMILY_UNKNOWN = "unknown";

    public static final String IZUM = IzumMunicipalParkingAdapter.SOURCE_KEY;
    public static final String ISPARK = IsparkMunicipalParkingAdapter.SOURCE_KEY;
    public static final String ANPARK = AnparkMunicipalParkingAdapter.SOURCE_KEY;
    public static final String KONYA = KonyaMunicipalParkingAdapter.SOURCE_KEY;
    public static final String OSM = OsmGeofabrikSourceKeys.SOURCE_KEY;
    public static final String FAKE_TEST =
            com.parkio.parking.infrastructure.fake.FakeTestMunicipalParkingAdapter.SOURCE_KEY;

    private MunicipalSourceIdentity() {}

    public static String familyOf(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return FAMILY_UNKNOWN;
        }
        if (IZUM.equals(sourceKey)) {
            return FAMILY_IZUM;
        }
        if (ISPARK.equals(sourceKey)) {
            return FAMILY_ISPARK;
        }
        if (ANPARK.equals(sourceKey)) {
            return FAMILY_ANPARK;
        }
        if (KONYA.equals(sourceKey)) {
            return FAMILY_KONYA;
        }
        if (OSM.equals(sourceKey)) {
            return FAMILY_OSM;
        }
        if (FAKE_TEST.equals(sourceKey) || sourceKey.startsWith("parkio-fake-")) {
            return FAMILY_FAKE_TEST;
        }
        if (IzelmanSourceKeys.ALL.contains(sourceKey) || sourceKey.startsWith("izelman-")) {
            return FAMILY_IZELMAN;
        }
        return FAMILY_UNKNOWN;
    }

    public static boolean isFakeTest(String sourceKey) {
        return FAMILY_FAKE_TEST.equals(familyOf(sourceKey));
    }

    public static boolean isIzum(String sourceKey) {
        return FAMILY_IZUM.equals(familyOf(sourceKey));
    }

    public static boolean isIspark(String sourceKey) {
        return FAMILY_ISPARK.equals(familyOf(sourceKey));
    }

    public static boolean isAnpark(String sourceKey) {
        return FAMILY_ANPARK.equals(familyOf(sourceKey));
    }

    public static boolean isKonya(String sourceKey) {
        return FAMILY_KONYA.equals(familyOf(sourceKey));
    }

    public static boolean isOsm(String sourceKey) {
        return FAMILY_OSM.equals(familyOf(sourceKey));
    }

    public static boolean isIzelman(String sourceKey) {
        return FAMILY_IZELMAN.equals(familyOf(sourceKey));
    }

    public static boolean isIzelmanFacilityInventory(String sourceKey) {
        return IzelmanSourceKeys.OPEN.equals(sourceKey)
                || IzelmanSourceKeys.CLOSED.equals(sourceKey)
                || IzelmanSourceKeys.BARRIER.equals(sourceKey);
    }

    public static boolean isIzelmanRoadside(String sourceKey) {
        return IzelmanSourceKeys.ROADSIDE.equals(sourceKey);
    }

    public static boolean isIzelmanTariff(String sourceKey) {
        return IzelmanSourceKeys.TARIFFS.equals(sourceKey);
    }

    public static Set<String> normalizeKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        return keys.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> parseLinkedKeys(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return normalizeKeys(java.util.Arrays.asList(csv.split(",")));
    }

    /** Fail closed: never treat human-readable text as source identity. */
    public static boolean looksLikeDisclaimerText(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String folded = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
        return folded.contains("IZELMAN") || folded.contains("OPENSTREETMAP");
    }
}