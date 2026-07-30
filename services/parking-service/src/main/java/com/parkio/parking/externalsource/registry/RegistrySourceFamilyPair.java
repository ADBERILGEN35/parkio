package com.parkio.parking.externalsource.registry;

import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import java.util.Locale;
import java.util.Set;

public enum RegistrySourceFamilyPair {
    IZUM_OSM(Family.IZUM, Family.OSM, true),
    IZUM_IZELMAN(Family.IZUM, Family.IZELMAN, false),
    OSM_IZELMAN(Family.OSM, Family.IZELMAN, false);

    public enum Family {
        IZUM, OSM, IZELMAN;

        static Family parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("source family is required");
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("unsupported source family: " + value, ex);
            }
        }
    }

    private final Family first;
    private final Family second;
    private final boolean enabled;

    RegistrySourceFamilyPair(Family first, Family second, boolean enabled) {
        this.first = first;
        this.second = second;
        this.enabled = enabled;
    }

    public static RegistrySourceFamilyPair resolve(String left, String right) {
        Family a = Family.parse(left);
        Family b = Family.parse(right);
        if (a == b) {
            throw new IllegalArgumentException("source families must differ");
        }
        for (RegistrySourceFamilyPair pair : values()) {
            if ((pair.first == a && pair.second == b) || (pair.first == b && pair.second == a)) {
                if (!pair.enabled) {
                    throw new IllegalArgumentException(pair.key() + " candidate generation is disabled");
                }
                return pair;
            }
        }
        throw new IllegalArgumentException("unsupported source family pair");
    }

    public String key() {
        return first.name().compareTo(second.name()) <= 0
                ? first.name() + "_" + second.name()
                : second.name() + "_" + first.name();
    }

    public Family leftFamily(String requestedLeft) {
        Family requested = Family.parse(requestedLeft);
        if (requested != first && requested != second) {
            throw new IllegalArgumentException("left family does not belong to pair");
        }
        return requested;
    }

    public Family other(Family family) {
        if (family == first) return second;
        if (family == second) return first;
        throw new IllegalArgumentException("family does not belong to pair");
    }

    public static Set<String> sourceKeys(Family family) {
        return switch (family) {
            case IZUM -> Set.of(MunicipalSourceIdentity.IZUM);
            case OSM -> Set.of(MunicipalSourceIdentity.OSM);
            case IZELMAN -> Set.of(IzelmanSourceKeys.OPEN, IzelmanSourceKeys.CLOSED, IzelmanSourceKeys.BARRIER);
        };
    }
}
