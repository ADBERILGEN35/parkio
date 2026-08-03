package com.parkio.parking.externalsource.district;

/**
 * DATA-WP-18 district coverage contract bounds.
 *
 * <p>Empty district counts mean zero currently active imported facilities assigned to that
 * geometry — not "no parking exists", insufficient parking, source failure, or demand shortage.
 */
public final class MunicipalDistrictCoveragePolicy {
    public static final String POLICY_VERSION = "municipal-district-coverage-v1";
    public static final String ASSET_VERSION = "izmir-ilceler-2024-10-18-v1";
    public static final int EXPECTED_DISTRICT_COUNT = 30;
    public static final String DEFAULT_NAME_PROPERTY = "adi";
    public static final String OFFICIAL_SOURCE_SHA256 =
            "6f4f43e4ce8139ddca4606582d903f047cb7c73810f8b876541a1ec3994ffd89";
    public static final int DEFAULT_MAX_FACILITIES = 10_000;
    public static final int DEFAULT_CACHE_TTL_SECONDS = 45;

    /** Bounded unavailable reasons (never include paths or raw exception text). */
    public static final String REASON_DISABLED = "disabled";
    public static final String REASON_ASSET_UNAVAILABLE = "asset_unavailable";
    public static final String REASON_ASSET_INVALID = "asset_invalid";
    public static final String REASON_FACILITY_LIMIT = "facility_limit_exceeded";

    private MunicipalDistrictCoveragePolicy() {}
}
