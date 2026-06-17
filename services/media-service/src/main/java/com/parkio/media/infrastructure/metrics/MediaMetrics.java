package com.parkio.media.infrastructure.metrics;

import com.parkio.media.domain.MediaStatus;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Upload and malware-scan outcome metrics exported at {@code /actuator/prometheus}.
 *
 * <ul>
 *   <li>{@code parkio.media.upload.count} / {@code parkio.media.rejected.count} —
 *       overall accepted vs. client-rejected uploads.</li>
 *   <li>{@code media_scan_clean_total} / {@code media_scan_rejected_total} /
 *       {@code media_scan_failed_total} — scan verdicts: clean, infected (rejected),
 *       and scans that could not be completed (fail-closed 503).</li>
 *   <li>{@code media_pending_scan_count} — gauge of media rows currently in
 *       {@code PENDING_SCAN}. Under the synchronous pipeline a row is committed only
 *       once it is READY (or rolled back), so this normally reads 0; it exists to
 *       detect stuck rows and to support a future asynchronous pipeline.</li>
 * </ul>
 *
 * <p>Counters only — no user id, file name, or matched signature is ever tagged.
 */
@Component
public class MediaMetrics {

    private final Counter uploads;
    private final Counter rejections;
    private final Counter scanClean;
    private final Counter scanRejected;
    private final Counter scanFailed;

    public MediaMetrics(MeterRegistry registry, MediaFileJpaRepository mediaFiles) {
        this.uploads = Counter.builder("parkio.media.upload.count")
                .description("Media uploads accepted and stored")
                .register(registry);
        this.rejections = Counter.builder("parkio.media.rejected.count")
                .description("Media uploads rejected by validation")
                .register(registry);
        this.scanClean = Counter.builder("media_scan_clean_total")
                .description("Media uploads that passed the malware scan")
                .register(registry);
        this.scanRejected = Counter.builder("media_scan_rejected_total")
                .description("Media uploads rejected by the malware scan (infected)")
                .register(registry);
        this.scanFailed = Counter.builder("media_scan_failed_total")
                .description("Media uploads whose malware scan could not be completed (fail-closed)")
                .register(registry);
        registry.gauge("media_pending_scan_count", mediaFiles,
                repo -> repo.countByStatus(MediaStatus.PENDING_SCAN));
    }

    public void uploadAccepted() {
        uploads.increment();
    }

    public void uploadRejected() {
        rejections.increment();
    }

    public void scanClean() {
        scanClean.increment();
    }

    public void scanRejected() {
        scanRejected.increment();
    }

    public void scanFailed() {
        scanFailed.increment();
    }
}
