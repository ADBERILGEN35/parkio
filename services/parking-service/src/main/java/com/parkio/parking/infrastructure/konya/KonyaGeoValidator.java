package com.parkio.parking.infrastructure.konya;

import org.springframework.stereotype.Component;

/**
 * Conservative Konya metropolitan bounding gate.
 *
 * <p>Discovery found copy-pasted coordinates around latitude ~39.88 (outside Konya).
 * Valid Konya facilities cluster around latitude ~37.x. This box excludes those
 * without snapping or relocating invalid geometry.
 */
@Component
public class KonyaGeoValidator {
    /** Evidence-based Konya province / metropolitan envelope (approximate). */
    static final double KONYA_LAT_MIN = 37.4;
    static final double KONYA_LAT_MAX = 38.6;
    static final double KONYA_LNG_MIN = 31.8;
    static final double KONYA_LNG_MAX = 34.2;

    public boolean isValidCoordinate(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return false;
        }
        return latitude >= KONYA_LAT_MIN
                && latitude <= KONYA_LAT_MAX
                && longitude >= KONYA_LNG_MIN
                && longitude <= KONYA_LNG_MAX;
    }
}
