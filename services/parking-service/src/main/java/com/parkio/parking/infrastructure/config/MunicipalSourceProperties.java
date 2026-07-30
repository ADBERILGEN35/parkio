package com.parkio.parking.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "parkio.municipal")
public class MunicipalSourceProperties {
    private boolean enabled;
    private boolean manualSyncEnabled;
    private Izum izum = new Izum();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isManualSyncEnabled() { return manualSyncEnabled; }
    public void setManualSyncEnabled(boolean manualSyncEnabled) { this.manualSyncEnabled = manualSyncEnabled; }
    public Izum getIzum() { return izum; }
    public void setIzum(Izum izum) { this.izum = izum; }

    public static class Izum {
        private boolean enabled;
        private String baseUrl = "https://openapi.izmir.bel.tr";
        private String path = "/api/ibb/izum/otoparklar";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(5);
        private int maxRetries = 2;
        private boolean schedulerEnabled;
        private long fixedDelayMs = 120000;
        private Long staleAfterSeconds;
        private Long agingAfterSeconds;
        private String userAgent = "ParkioParkingService/1.0 (+https://parkio.dev)";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = Math.max(0, maxRetries); }
        public boolean isSchedulerEnabled() { return schedulerEnabled; }
        public void setSchedulerEnabled(boolean schedulerEnabled) { this.schedulerEnabled = schedulerEnabled; }
        public long getFixedDelayMs() { return fixedDelayMs; }
        public void setFixedDelayMs(long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
        public Long getStaleAfterSeconds() { return staleAfterSeconds; }
        public void setStaleAfterSeconds(Long staleAfterSeconds) { this.staleAfterSeconds = staleAfterSeconds; }
        public Long getAgingAfterSeconds() { return agingAfterSeconds; }
        public void setAgingAfterSeconds(Long agingAfterSeconds) { this.agingAfterSeconds = agingAfterSeconds; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    }
}
