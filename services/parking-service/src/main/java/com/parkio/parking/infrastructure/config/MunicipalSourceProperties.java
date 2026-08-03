package com.parkio.parking.infrastructure.config;

import com.parkio.parking.externalsource.MunicipalSourceOperatingMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "parkio.municipal")
public class MunicipalSourceProperties {
    private boolean enabled;
    private boolean manualSyncEnabled;
    private Izum izum = new Izum();
    private Osm osm = new Osm();
    private Sla sla = new Sla();
    private Discovery discovery = new Discovery();
    private Ops ops = new Ops();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isManualSyncEnabled() { return manualSyncEnabled; }
    public void setManualSyncEnabled(boolean manualSyncEnabled) { this.manualSyncEnabled = manualSyncEnabled; }
    public Izum getIzum() { return izum; }
    public void setIzum(Izum izum) { this.izum = izum; }
    public Osm getOsm() { return osm; }
    public void setOsm(Osm osm) { this.osm = osm; }
    public Sla getSla() { return sla; }
    public void setSla(Sla sla) { this.sla = sla; }
    public Discovery getDiscovery() { return discovery; }
    public void setDiscovery(Discovery discovery) { this.discovery = discovery; }
    public Ops getOps() { return ops; }
    public void setOps(Ops ops) { this.ops = ops; }

    /**
     * Operator-facing municipal ops controls (DATA-WP-15 / DATA-WP-16).
     * Quality report is read-only aggregates; never triggers sync, import or linking.
     * Source-mode SLA is independent of the quality-report flag.
     * Canonical defaults off everywhere; leave-on gates are DATA-WP-15A / DATA-WP-16A.
     */
    public static class Ops {
        private boolean qualityReportEnabled = false;
        private boolean sourceModeSlaEnabled = false;
        private int recentRunLimitDefault = 20;
        private int recentRunLimitMax = 100;

        public boolean isQualityReportEnabled() { return qualityReportEnabled; }
        public void setQualityReportEnabled(boolean qualityReportEnabled) {
            this.qualityReportEnabled = qualityReportEnabled;
        }
        public boolean isSourceModeSlaEnabled() { return sourceModeSlaEnabled; }
        public void setSourceModeSlaEnabled(boolean sourceModeSlaEnabled) {
            this.sourceModeSlaEnabled = sourceModeSlaEnabled;
        }
        /** Clamped into [1, max] because binding order of the two limits is not guaranteed. */
        public int getRecentRunLimitDefault() {
            return Math.min(Math.max(1, recentRunLimitDefault), getRecentRunLimitMax());
        }
        public void setRecentRunLimitDefault(int recentRunLimitDefault) {
            this.recentRunLimitDefault = recentRunLimitDefault;
        }
        public int getRecentRunLimitMax() { return Math.max(1, recentRunLimitMax); }
        public void setRecentRunLimitMax(int recentRunLimitMax) {
            this.recentRunLimitMax = recentRunLimitMax;
        }
    }

    /**
     * Nearby duplicate-presentation controls (DATA-WP-07 / DATA-WP-12).
     * Canonical default on; production profile pins false until separate approval.
     * Query-time only; never mutates registry state.
     */
    public static class Discovery {
        private boolean duplicatePresentationEnabled = true;
        private double duplicateRadiusMeters = 100.0;
        private int overfetchFactor = 2;
        private int overfetchAbsoluteMax = 200;
        private java.util.List<String> supportedPairs = java.util.List.of("IZUM_OSM");

        public boolean isDuplicatePresentationEnabled() { return duplicatePresentationEnabled; }
        public void setDuplicatePresentationEnabled(boolean duplicatePresentationEnabled) {
            this.duplicatePresentationEnabled = duplicatePresentationEnabled;
        }
        public double getDuplicateRadiusMeters() { return duplicateRadiusMeters; }
        public void setDuplicateRadiusMeters(double duplicateRadiusMeters) {
            this.duplicateRadiusMeters = duplicateRadiusMeters;
        }
        public int getOverfetchFactor() { return overfetchFactor; }
        public void setOverfetchFactor(int overfetchFactor) {
            this.overfetchFactor = Math.max(1, overfetchFactor);
        }
        public int getOverfetchAbsoluteMax() { return overfetchAbsoluteMax; }
        public void setOverfetchAbsoluteMax(int overfetchAbsoluteMax) {
            this.overfetchAbsoluteMax = Math.max(1, overfetchAbsoluteMax);
        }
        public java.util.List<String> getSupportedPairs() { return supportedPairs; }
        public void setSupportedPairs(java.util.List<String> supportedPairs) {
            this.supportedPairs = supportedPairs == null ? java.util.List.of() : supportedPairs;
        }
    }

    /**
     * Operational SLA thresholds (integration health). Distinct from occupancy
     * freshness aging/stale seconds on the source row.
     */
    public static class Sla {
        private int warningConsecutiveFailures = 3;
        private int criticalConsecutiveFailures = 5;
        private long warningSecondsSinceSuccess = 600;
        private long criticalSecondsSinceSuccess = 1800;
        private long staleRunningAfterSeconds = 600;
        private long recoveringWindowSeconds = 900;
        private long failureWindowSeconds = 86400;

        public int getWarningConsecutiveFailures() { return warningConsecutiveFailures; }
        public void setWarningConsecutiveFailures(int warningConsecutiveFailures) {
            this.warningConsecutiveFailures = warningConsecutiveFailures;
        }
        public int getCriticalConsecutiveFailures() { return criticalConsecutiveFailures; }
        public void setCriticalConsecutiveFailures(int criticalConsecutiveFailures) {
            this.criticalConsecutiveFailures = criticalConsecutiveFailures;
        }
        public long getWarningSecondsSinceSuccess() { return warningSecondsSinceSuccess; }
        public void setWarningSecondsSinceSuccess(long warningSecondsSinceSuccess) {
            this.warningSecondsSinceSuccess = warningSecondsSinceSuccess;
        }
        public long getCriticalSecondsSinceSuccess() { return criticalSecondsSinceSuccess; }
        public void setCriticalSecondsSinceSuccess(long criticalSecondsSinceSuccess) {
            this.criticalSecondsSinceSuccess = criticalSecondsSinceSuccess;
        }
        public long getStaleRunningAfterSeconds() { return staleRunningAfterSeconds; }
        public void setStaleRunningAfterSeconds(long staleRunningAfterSeconds) {
            this.staleRunningAfterSeconds = staleRunningAfterSeconds;
        }
        public long getRecoveringWindowSeconds() { return recoveringWindowSeconds; }
        public void setRecoveringWindowSeconds(long recoveringWindowSeconds) {
            this.recoveringWindowSeconds = recoveringWindowSeconds;
        }
        public long getFailureWindowSeconds() { return failureWindowSeconds; }
        public void setFailureWindowSeconds(long failureWindowSeconds) {
            this.failureWindowSeconds = failureWindowSeconds;
        }
    }

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
        /** Explicit SLA mode; never inferred from schedulerEnabled alone. */
        private MunicipalSourceOperatingMode operatingMode = MunicipalSourceOperatingMode.SCHEDULED;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public MunicipalSourceOperatingMode getOperatingMode() { return operatingMode; }
        public void setOperatingMode(MunicipalSourceOperatingMode operatingMode) {
            this.operatingMode = operatingMode == null
                    ? MunicipalSourceOperatingMode.SCHEDULED
                    : operatingMode;
        }
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
        private String clipVersion = "izmir-admin-izbb-2024-10-18-v1";
        /**
         * DATA-WP-13 display-label policy. {@code osm-label-v1} (default) or {@code legacy}
         * technical fallback. Independent of import/publication/linking flags.
         */
        private String labelPolicy = "osm-label-v1";
        /** Operator boundary directory (Windows or hosted-beta Linux). Empty = envelope fallback. */
        private String boundaryDir = "";
        private String boundaryGeojsonFilename = "izmir-admin-boundary.geojson";
        private String boundaryPolyFilename = "izmir-admin-boundary.poly";
        /** Expected SHA-256 of derived admin GeoJSON; empty skips checksum enforcement. */
        private String boundaryGeojsonSha256 = "";
        /** Expected SHA-256 of official source ilceler GeoJSON; empty skips. */
        private String boundarySourceSha256 =
                "6f4f43e4ce8139ddca4606582d903f047cb7c73810f8b876541a1ec3994ffd89";
        /** Explicit SLA mode; never inferred from schedulerEnabled alone. */
        private MunicipalSourceOperatingMode operatingMode =
                MunicipalSourceOperatingMode.OPERATOR_IMPORTED;

        public boolean isImportEnabled() { return importEnabled; }
        public MunicipalSourceOperatingMode getOperatingMode() { return operatingMode; }
        public void setOperatingMode(MunicipalSourceOperatingMode operatingMode) {
            this.operatingMode = operatingMode == null
                    ? MunicipalSourceOperatingMode.OPERATOR_IMPORTED
                    : operatingMode;
        }
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
        public String getLabelPolicy() { return labelPolicy; }
        public void setLabelPolicy(String labelPolicy) { this.labelPolicy = labelPolicy; }
        public String getBoundaryDir() { return boundaryDir; }
        public void setBoundaryDir(String boundaryDir) { this.boundaryDir = boundaryDir; }
        public String getBoundaryGeojsonFilename() { return boundaryGeojsonFilename; }
        public void setBoundaryGeojsonFilename(String boundaryGeojsonFilename) {
            this.boundaryGeojsonFilename = boundaryGeojsonFilename;
        }
        public String getBoundaryPolyFilename() { return boundaryPolyFilename; }
        public void setBoundaryPolyFilename(String boundaryPolyFilename) {
            this.boundaryPolyFilename = boundaryPolyFilename;
        }
        public String getBoundaryGeojsonSha256() { return boundaryGeojsonSha256; }
        public void setBoundaryGeojsonSha256(String boundaryGeojsonSha256) {
            this.boundaryGeojsonSha256 = boundaryGeojsonSha256;
        }
        public String getBoundarySourceSha256() { return boundarySourceSha256; }
        public void setBoundarySourceSha256(String boundarySourceSha256) {
            this.boundarySourceSha256 = boundarySourceSha256;
        }
    }
}