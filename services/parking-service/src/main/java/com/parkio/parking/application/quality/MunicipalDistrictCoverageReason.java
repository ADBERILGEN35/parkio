package com.parkio.parking.application.quality;

import com.parkio.parking.externalsource.district.MunicipalDistrictCoveragePolicy;

/** Bounded unavailable/disabled reasons for district coverage (no paths/exceptions). */
public final class MunicipalDistrictCoverageReason {
    public static final String DISABLED = MunicipalDistrictCoveragePolicy.REASON_DISABLED;
    public static final String ASSET_UNAVAILABLE = MunicipalDistrictCoveragePolicy.REASON_ASSET_UNAVAILABLE;
    public static final String ASSET_INVALID = MunicipalDistrictCoveragePolicy.REASON_ASSET_INVALID;
    public static final String FACILITY_LIMIT = MunicipalDistrictCoveragePolicy.REASON_FACILITY_LIMIT;
    public static final String TOPOLOGY_INVALID = MunicipalDistrictCoveragePolicy.REASON_TOPOLOGY_INVALID;

    private MunicipalDistrictCoverageReason() {}
}
