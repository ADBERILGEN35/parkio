package com.parkio.parking.application.recommendation;

import com.parkio.parking.externalsource.osm.ConflationPolicy;

/** Haversine distance helper for recommendation candidate mapping. */
final class RecommendationDistances {

    private RecommendationDistances() {}

    static int meters(double fromLat, double fromLng, double toLat, double toLng) {
        double meters = ConflationPolicy.haversineMeters(fromLat, fromLng, toLat, toLng);
        if (!Double.isFinite(meters) || meters < 0) {
            return 0;
        }
        return (int) Math.round(meters);
    }
}
