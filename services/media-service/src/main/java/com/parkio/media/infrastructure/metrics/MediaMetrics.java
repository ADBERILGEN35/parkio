package com.parkio.media.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Upload outcome counters exported at {@code /actuator/prometheus}:
 * {@code parkio.media.upload.count} and {@code parkio.media.rejected.count}.
 *
 * <p>Counters only — no user id, file name, or other PII is ever tagged. A spike in
 * rejections signals abusive uploads or an over-strict validation change.
 */
@Component
public class MediaMetrics {

    private final Counter uploads;
    private final Counter rejections;

    public MediaMetrics(MeterRegistry registry) {
        this.uploads = Counter.builder("parkio.media.upload.count")
                .description("Media uploads accepted and stored")
                .register(registry);
        this.rejections = Counter.builder("parkio.media.rejected.count")
                .description("Media uploads rejected by validation")
                .register(registry);
    }

    public void uploadAccepted() {
        uploads.increment();
    }

    public void uploadRejected() {
        rejections.increment();
    }
}
