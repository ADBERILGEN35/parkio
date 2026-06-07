package com.parkio.parking.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code parkio.parking.*}: tunable nearby-search defaults. */
@ConfigurationProperties(prefix = "parkio.parking")
public class ParkingProperties {

    private Search search = new Search();

    public Search getSearch() {
        return search;
    }

    public void setSearch(Search search) {
        this.search = search;
    }

    public static class Search {

        private double defaultRadiusMeters = 1000;
        private int defaultResultLimit = 10;
        private double maxRadiusMeters = 50000;
        private int maxResultLimit = 50;

        public double getDefaultRadiusMeters() {
            return defaultRadiusMeters;
        }

        public void setDefaultRadiusMeters(double defaultRadiusMeters) {
            this.defaultRadiusMeters = defaultRadiusMeters;
        }

        public int getDefaultResultLimit() {
            return defaultResultLimit;
        }

        public void setDefaultResultLimit(int defaultResultLimit) {
            this.defaultResultLimit = defaultResultLimit;
        }

        public double getMaxRadiusMeters() {
            return maxRadiusMeters;
        }

        public void setMaxRadiusMeters(double maxRadiusMeters) {
            this.maxRadiusMeters = maxRadiusMeters;
        }

        public int getMaxResultLimit() {
            return maxResultLimit;
        }

        public void setMaxResultLimit(int maxResultLimit) {
            this.maxResultLimit = maxResultLimit;
        }
    }
}
