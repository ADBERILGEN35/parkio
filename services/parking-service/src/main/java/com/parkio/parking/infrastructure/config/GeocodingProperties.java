package com.parkio.parking.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code parkio.geocoding.*}: provider endpoint, request bias, timeouts and cache TTLs. */
@ConfigurationProperties(prefix = "parkio.geocoding")
public class GeocodingProperties {

    private Provider provider = new Provider();
    private Cache cache = new Cache();

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public static class Provider {

        /** Base URL of the geocoding provider (no trailing slash needed). */
        private String baseUrl = "https://nominatim.openstreetmap.org";
        /** Descriptive User-Agent — Nominatim's usage policy requires identifying the app. */
        private String userAgent = "Parkio/1.0 (+https://parkio.app)";
        /** ISO country bias (Nominatim {@code countrycodes}). */
        private String countryCodes = "tr";
        /** Preferred result language (Nominatim {@code accept-language}). */
        private String language = "tr";
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(3);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public String getCountryCodes() {
            return countryCodes;
        }

        public void setCountryCodes(String countryCodes) {
            this.countryCodes = countryCodes;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class Cache {

        /** TTL for non-empty results (places rarely move). */
        private Duration positiveTtl = Duration.ofHours(24);
        /** TTL for empty results — short, so a typo'd or too-soon query retries quickly. */
        private Duration negativeTtl = Duration.ofMinutes(5);

        public Duration getPositiveTtl() {
            return positiveTtl;
        }

        public void setPositiveTtl(Duration positiveTtl) {
            this.positiveTtl = positiveTtl;
        }

        public Duration getNegativeTtl() {
            return negativeTtl;
        }

        public void setNegativeTtl(Duration negativeTtl) {
            this.negativeTtl = negativeTtl;
        }
    }
}
