package com.parkio.aivalidation.infrastructure.vision;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer instrumentation for the vision pipeline.
 */
public class VisionMetrics {

    private final MeterRegistry registry;
    private final String provider;
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(0);

    public VisionMetrics(MeterRegistry registry, String provider) {
        this.registry = registry;
        this.provider = provider;
        registry.gauge("parkio.ai.vision.last_success_epoch_seconds", lastSuccessEpochSeconds);
    }

    public void recordOutcome(String outcome, Duration duration) {
        registry.counter("parkio.ai.vision.validations",
                "provider", provider, "outcome", outcome).increment();
        Timer.builder("parkio.ai.vision.duration")
                .description("End-to-end vision classification latency")
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
    }

    public void recordProviderError(String category) {
        registry.counter("parkio.ai.vision.provider.errors",
                "provider", provider, "category", category).increment();
    }

    public void recordFailClosed(String reason) {
        registry.counter("parkio.ai.vision.fail.closed",
                "provider", provider, "reason", reason).increment();
    }

    /** A prior result was reused because its version tuple matched the current one. */
    public void recordReuse() {
        registry.counter("parkio.ai.vision.reuse", "provider", provider).increment();
    }

    /**
     * A prior result was NOT reused because its model/prompt/policy/threshold version
     * (or a legacy incomplete tuple) differed from the current configuration; the
     * classifier re-ran instead of reusing a cross-version decision.
     */
    public void recordVersionMismatchRerun() {
        registry.counter("parkio.ai.vision.reuse.version_mismatch", "provider", provider).increment();
    }

    public void recordUsage(VisionProviderClient.VisionAnalysis.Usage usage) {
        if (usage == null) {
            return;
        }
        registry.counter("parkio.ai.vision.tokens",
                "provider", provider, "type", "prompt").increment(usage.promptTokens());
        registry.counter("parkio.ai.vision.tokens",
                "provider", provider, "type", "candidates").increment(usage.candidatesTokens());
        registry.counter("parkio.ai.vision.tokens",
                "provider", provider, "type", "total").increment(usage.totalTokens());
    }

    public void recordBytes(int originalBytes, int renditionBytes) {
        registry.summary("parkio.ai.vision.image_bytes", "provider", provider, "stage", "original")
                .record(originalBytes);
        registry.summary("parkio.ai.vision.image_bytes", "provider", provider, "stage", "rendition")
                .record(renditionBytes);
    }

    public void recordModerationDecision(String decision, String reasonCode) {
        registry.counter("parkio.ai.vision.moderation.decision",
                "provider", provider, "decision", decision).increment();
        if (reasonCode != null && !reasonCode.isBlank()) {
            registry.counter("parkio.ai.vision.moderation.reason",
                    "provider", provider, "reason_code", reasonCode).increment();
        }
    }

    public void markSuccess() {
        lastSuccessEpochSeconds.set(System.currentTimeMillis() / 1000L);
    }

    public long lastSuccessEpochSeconds() {
        return lastSuccessEpochSeconds.get();
    }
}
