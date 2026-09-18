package com.parkio.parking.infrastructure.kayseri;

import org.springframework.stereotype.Component;

/**
 * Conservative geographic gate for Kayseri metropolitan / provincial coverage.
 *
 * <p>Observed official lots cluster near lat≈38.72, lng≈35.49. Bounds intentionally
 * cover the municipal region without snapping invalid points into the city.
 */
@Component
public class KayseriGeoValidator {
    static final double LAT_MIN = 38.40;
    static final double LAT_MAX = 39.20;
    static final double LNG_MIN = 35.00;
    static final double LNG_MAX = 36.20;

    public boolean isValid(double latitude, double longitude) {
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= LAT_MIN
                && latitude <= LAT_MAX
                && longitude >= LNG_MIN
                && longitude <= LNG_MAX;
    }
}
