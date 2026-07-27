package com.parkio.aivalidation.infrastructure.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code parkio.ai.vision.*}: which content classifier backs the AI gate and,
 * for the real provider, connection/limits/threshold settings.
 *
 * <p>{@code provider} selects the {@link com.parkio.aivalidation.domain.ContentRiskClassifier}
 * implementation: {@code heuristic} (fail-closed placeholder, local dev/tests) or
 * {@code gemini} (real vision model; hosted-beta/production). The API key is injected
 * via environment only — never hardcoded, never logged.
 */
@ConfigurationProperties(prefix = "parkio.ai.vision")
public class VisionProperties {

    /** Classifier backing the AI gate: {@code heuristic} or {@code gemini}. */
    private String provider = "heuristic";

    /**
     * Hard cap on <em>final</em> vision payload bytes after downscale (default 1.5 MiB).
     * Larger renditions fail closed (UNCERTAIN → PENDING_REVIEW).
     */
    private long maxImageBytes = 1_500_000L;

    /**
     * Cap on encoded bytes fetched from media-service before decode/downscale.
     * Checked via Content-Length and bounded stream reads.
     */
    private long maxFetchBytes = 15L * 1024 * 1024;

    /** Decoded pixel bomb limit (width × height). */
    private long maxDecodedPixels = 40_000_000L;

    /** Max source edge before decode reject. */
    private int maxSourceEdge = 8000;

    /** Longest edge of the vision rendition (pixels). */
    private int targetLongestEdge = 1536;

    /** JPEG quality for the vision rendition (0..1). */
    private float jpegQuality = 0.85f;

    /**
     * How long a genuine SEMANTIC UNCERTAIN result may be reused without another
     * provider call. Infrastructure fail-closed results are never reused this way.
     */
    private Duration semanticUncertainReuseTtl = Duration.ofHours(24);

    /** Content types the vision pipeline accepts (mirrors media-service's allow-list). */
    private List<String> allowedContentTypes = List.of("image/jpeg", "image/png", "image/webp");

    private final Revalidation revalidation = new Revalidation();

    /**
     * Minimum provider confidence for LIKELY_PARKING to stand; below it the verdict
     * degrades to UNCERTAIN (human review) rather than publishing on a weak signal.
     */
    /** Legacy accept floor; prefer {@link #decision} thresholds for new policy. */
    private double acceptConfidence = 0.65;

    /** Legacy reject floor; prefer {@link #decision} thresholds for new policy. */
    private double rejectConfidence = 0.95;

    private final Decision decision = new Decision();

    /**
     * Prompt/template version recorded on every result for traceability and to gate
     * cross-version result reuse. Bump this whenever {@code GeminiVisionClient.PROMPT}
     * or the response schema changes in a way that can alter verdicts.
     */
    private String promptVersion = "2026-07-whole-image-v1";

    /** Bump when {@link ModerationDecisionPolicy} or reason-code routing changes. */
    private String policyVersion = "2026-07-photo-policy-v2";

    private final MediaClient mediaClient = new MediaClient();
    private final Gemini gemini = new Gemini();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(long maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    public long getMaxFetchBytes() {
        return maxFetchBytes;
    }

    public void setMaxFetchBytes(long maxFetchBytes) {
        this.maxFetchBytes = maxFetchBytes;
    }

    public long getMaxDecodedPixels() {
        return maxDecodedPixels;
    }

    public void setMaxDecodedPixels(long maxDecodedPixels) {
        this.maxDecodedPixels = maxDecodedPixels;
    }

    public int getMaxSourceEdge() {
        return maxSourceEdge;
    }

    public void setMaxSourceEdge(int maxSourceEdge) {
        this.maxSourceEdge = maxSourceEdge;
    }

    public int getTargetLongestEdge() {
        return targetLongestEdge;
    }

    public void setTargetLongestEdge(int targetLongestEdge) {
        this.targetLongestEdge = targetLongestEdge;
    }

    public float getJpegQuality() {
        return jpegQuality;
    }

    public void setJpegQuality(float jpegQuality) {
        this.jpegQuality = jpegQuality;
    }

    public Duration getSemanticUncertainReuseTtl() {
        return semanticUncertainReuseTtl;
    }

    public void setSemanticUncertainReuseTtl(Duration semanticUncertainReuseTtl) {
        this.semanticUncertainReuseTtl = semanticUncertainReuseTtl;
    }

    public Revalidation getRevalidation() {
        return revalidation;
    }

    public List<String> getAllowedContentTypes() {
        return allowedContentTypes;
    }

    public void setAllowedContentTypes(List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public double getAcceptConfidence() {
        return acceptConfidence;
    }

    public void setAcceptConfidence(double acceptConfidence) {
        this.acceptConfidence = acceptConfidence;
    }

    public double getRejectConfidence() {
        return rejectConfidence;
    }

    public void setRejectConfidence(double rejectConfidence) {
        this.rejectConfidence = rejectConfidence;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    /**
     * Deterministically derived from the accept/reject thresholds so a threshold change
     * automatically invalidates cross-version result reuse without a manual bump.
     */
    public Decision getDecision() {
        return decision;
    }

    public String getThresholdVersion() {
        return String.format(Locale.ROOT, "acc%.2f-rej%.2f-p%.2f-o%.2f-i%.2f-u%.2f",
                decision.parkingContextAcceptConfidence,
                decision.openSpaceAcceptConfidence,
                decision.clearlyIrrelevantAcceptMax,
                decision.clearlyIrrelevantRejectConfidence,
                decision.unusableImageRejectConfidence,
                rejectConfidence);
    }

    /** Central three-way decision thresholds — single source of truth. */
    public static class Decision {

        private double parkingContextAcceptConfidence = 0.65;
        private double openSpaceAcceptConfidence = 0.55;
        private double clearlyIrrelevantAcceptMax = 0.20;
        private double clearlyIrrelevantRejectConfidence = 0.95;
        private double unusableImageRejectConfidence = 0.95;

        public double getParkingContextAcceptConfidence() {
            return parkingContextAcceptConfidence;
        }

        public void setParkingContextAcceptConfidence(double parkingContextAcceptConfidence) {
            this.parkingContextAcceptConfidence = parkingContextAcceptConfidence;
        }

        public double getOpenSpaceAcceptConfidence() {
            return openSpaceAcceptConfidence;
        }

        public void setOpenSpaceAcceptConfidence(double openSpaceAcceptConfidence) {
            this.openSpaceAcceptConfidence = openSpaceAcceptConfidence;
        }

        public double getClearlyIrrelevantAcceptMax() {
            return clearlyIrrelevantAcceptMax;
        }

        public void setClearlyIrrelevantAcceptMax(double clearlyIrrelevantAcceptMax) {
            this.clearlyIrrelevantAcceptMax = clearlyIrrelevantAcceptMax;
        }

        public double getClearlyIrrelevantRejectConfidence() {
            return clearlyIrrelevantRejectConfidence;
        }

        public void setClearlyIrrelevantRejectConfidence(double clearlyIrrelevantRejectConfidence) {
            this.clearlyIrrelevantRejectConfidence = clearlyIrrelevantRejectConfidence;
        }

        public double getUnusableImageRejectConfidence() {
            return unusableImageRejectConfidence;
        }

        public void setUnusableImageRejectConfidence(double unusableImageRejectConfidence) {
            this.unusableImageRejectConfidence = unusableImageRejectConfidence;
        }
    }

    public MediaClient getMediaClient() {
        return mediaClient;
    }

    public Gemini getGemini() {
        return gemini;
    }

    /** Internal media-service content endpoint client settings. */
    public static class MediaClient {

        private String baseUrl = "http://localhost:8084";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(5);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
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

    /** Scheduled recovery for infrastructure fail-closed validations. */
    public static class Revalidation {
        private boolean enabled = true;
        private long fixedDelayMs = 300_000L;
        private Duration minAge = Duration.ofMinutes(5);
        private Duration maxAge = Duration.ofDays(7);
        private int batchSize = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }

        public Duration getMinAge() {
            return minAge;
        }

        public void setMinAge(Duration minAge) {
            this.minAge = minAge;
        }

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    /** Gemini vision provider settings (parkio.ai.vision.gemini.*). */
    public static class Gemini {

        /** API key — environment-injected secret; never defaulted, logged, or committed. */
        private String apiKey = "";
        /** Vision-capable model id; override per environment without a rebuild. */
        private String model = "gemini-2.5-flash-lite";
        /**
         * Optional explicit model version recorded for traceability. When blank, the
         * resolved {@link #model} id is persisted as the model version. Gemini
         * {@code flash-lite} exposes only a moving alias (dated snapshots are retired),
         * so exact provider-side pinning is not possible; we persist what we sent.
         */
        private String modelVersion = "";
        /**
         * Deterministic decoding seed sent to the provider. Combined with
         * {@code temperature=0}, this maximizes reproducibility. The provider does not
         * contractually guarantee determinism, so results still carry full provenance.
         */
        private int seed = 12345;
        /** API origin; tests point this at a local stub server. */
        private String baseUrl = "https://generativelanguage.googleapis.com";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(15);
        /** Extra attempts after the first call for 429/5xx/timeout (bounded; never infinite). */
        private int maxRetries = 1;
        /** Upper bound honoured for a provider Retry-After hint between attempts. */
        private Duration maxRetryDelay = Duration.ofSeconds(2);

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getModelVersion() {
            return modelVersion;
        }

        public void setModelVersion(String modelVersion) {
            this.modelVersion = modelVersion;
        }

        public int getSeed() {
            return seed;
        }

        public void setSeed(int seed) {
            this.seed = seed;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
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

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Duration getMaxRetryDelay() {
            return maxRetryDelay;
        }

        public void setMaxRetryDelay(Duration maxRetryDelay) {
            this.maxRetryDelay = maxRetryDelay;
        }
    }
}
