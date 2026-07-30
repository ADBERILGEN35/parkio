package com.parkio.parking.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "parkio.municipal")
public class MunicipalSourceProperties {
    private boolean enabled;
    private boolean manualSyncEnabled;
    private Izum izum = new Izum();
    private Osm osm = new Osm();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isManualSyncEnabled() { return manualSyncEnabled; }
    public void setManualSyncEnabled(boolean manualSyncEnabled) { this.manualSyncEnabled = manualSyncEnabled; }
    public Izum getIzum() { return izum; }
    public void setIzum(Izum izum) { this.izum = izum; }
    public Osm getOsm() { return osm; }
    public void setOsm(Osm osm) { this.osm = osm; }

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

    public static class Osm {
        private boolean importEnabled;
        private boolean schedulerEnabled;
        private boolean conflationEnabled;
        private boolean autoMatchEnabled;
        private boolean publishRestricted;
        private boolean publicationEnabled;
        private String localInputPath = "";
        private String allowedInputDir = "";
        private long maxInputBytes = 52_428_800L; // 50 MiB interchange cap
        private String clipVersion = "izmir-bbox-v1";

        public boolean isImportEnabled() { return importEnabled; }
        public void setImportEnabled(boolean importEnabled) { this.importEnabled = importEnabled; }
        public boolean isSchedulerEnabled() { return schedulerEnabled; }
        public void setSchedulerEnabled(boolean schedulerEnabled) { this.schedulerEnabled = schedulerEnabled; }
        public boolean isConflationEnabled() { return conflationEnabled; }
        public void setConflationEnabled(boolean conflationEnabled) { this.conflationEnabled = conflationEnabled; }
        public boolean isAutoMatchEnabled() { return autoMatchEnabled; }
        public void setAutoMatchEnabled(boolean autoMatchEnabled) { this.autoMatchEnabled = autoMatchEnabled; }
        public boolean isPublishRestricted() { return publishRestricted; }
        public void setPublishRestricted(boolean publishRestricted) { this.publishRestricted = publishRestricted; }
        public boolean isPublicationEnabled() { return publicationEnabled; }
        public void setPublicationEnabled(boolean publicationEnabled) { this.publicationEnabled = publicationEnabled; }
        public String getLocalInputPath() { return localInputPath; }
        public void setLocalInputPath(String localInputPath) { this.localInputPath = localInputPath; }
        public String getAllowedInputDir() { return allowedInputDir; }
        public void setAllowedInputDir(String allowedInputDir) { this.allowedInputDir = allowedInputDir; }
        public long getMaxInputBytes() { return maxInputBytes; }
        public void setMaxInputBytes(long maxInputBytes) { this.maxInputBytes = maxInputBytes; }
        public String getClipVersion() { return clipVersion; }
        public void setClipVersion(String clipVersion) { this.clipVersion = clipVersion; }
    }
}