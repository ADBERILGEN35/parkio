package com.parkio.parking.externalsource.district;

/**
 * DATA-WP-19 İzmir district topology policy (normalized derived asset + JTS covers).
 *
 * <p>Root cause of DATA-WP-18A anomalies: custom even-odd ray casting produced false-positive
 * {@code covers} results on valid İzBB polygons (Karşıyaka shadowed by Çiğli via folded-name
 * tie-break). PostGIS {@code ST_Covers} after {@code ST_MakeValid} assigns uniquely.
 */
public final class MunicipalDistrictTopologyPolicy {
    public static final String TOPOLOGY_POLICY_VERSION = "izmir-district-topology-v1";
    public static final String NORMALIZED_ASSET_VERSION = "izmir-districts-izbb-2024-10-18-topology-v1";

    /** Operator-derived normalized FeatureCollection SHA-256 (DATA-WP-19 export). */
    public static final String NORMALIZED_ASSET_SHA256 =
            "0c7457122d13fa02eba1258b6cda5cc28bfb7d64150e4e7db131f40611a655ec";

    public static final String REASON_TOPOLOGY_INVALID = "topology_invalid";
    public static final String REASON_TOPOLOGY_DISABLED = "topology_disabled";

    /**
     * Deg² area threshold below which pairwise intersection is treated as shared-boundary noise
     * (measured PostGIS max residual ~1e-9 after MakeValid).
     */
    public static final double MATERIAL_OVERLAP_AREA_DEG2 = 1.0e-8;

    private MunicipalDistrictTopologyPolicy() {}
}
