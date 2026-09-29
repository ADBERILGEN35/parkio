package com.parkio.parking.externalsource.izelman;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public final class IzelmanSourceKeys {
    public static final String OPEN = "izelman-open-parking-facilities";
    public static final String CLOSED = "izelman-closed-parking-facilities";
    public static final String BARRIER = "izelman-barrier-parking-facilities";
    public static final String ROADSIDE = "izelman-roadside-parking";
    public static final String TARIFFS = "izelman-parking-tariffs";
    public static final Set<String> ALL = Set.of(OPEN, CLOSED, BARRIER, ROADSIDE, TARIFFS);
    public static final Map<String, Instant> CONTENT_DATES = Map.of(
            OPEN, Instant.parse("2022-11-28T00:00:00Z"),
            CLOSED, Instant.parse("2022-11-25T00:00:00Z"),
            BARRIER, Instant.parse("2022-11-28T00:00:00Z"),
            ROADSIDE, Instant.parse("2022-11-25T00:00:00Z"),
            TARIFFS, Instant.parse("2024-09-02T00:00:00Z"));

    private IzelmanSourceKeys() {}
}
