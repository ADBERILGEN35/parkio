package com.parkio.gateway.infrastructure.waitlist.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Moves committed outbox rows into the slack_biz waitlist inbox as sanitized
 * JSON envelopes. Runs off the request path on the scheduler thread.
 *
 * <p>Delivery contract: at-least-once into the inbox. A crash between the file
 * write and {@code markExported} rewrites the same file name on the next poll;
 * the relay suppresses the repeat by {@code dedupKey}. Export failures retry
 * with bounded exponential backoff and become {@code FAILED} after
 * {@code maxExportAttempts}; they are never retried indefinitely.
 */
public class WaitlistOpsNotificationExporter {

    static final String PRODUCER = "gateway-waitlist-outbox";
    static final int CONTRACT_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(WaitlistOpsNotificationExporter.class);

    private final JdbcWaitlistOpsNotificationOutbox outbox;
    private final WaitlistOpsNotificationProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WaitlistOpsNotificationExporter(
            JdbcWaitlistOpsNotificationOutbox outbox,
            WaitlistOpsNotificationProperties properties,
            Clock clock) {
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${parkio.waitlist.ops-notifications.poll-interval:PT30S}",
            initialDelayString = "${parkio.waitlist.ops-notifications.poll-interval:PT30S}")
    public void scheduledExport() {
        try {
            exportDue();
        } catch (RuntimeException ex) {
            log.warn("Waitlist ops notification export poll failed; category={}", ex.getClass().getSimpleName());
        }
    }

    public ExportResult exportDue() {
        if (!outbox.isActive()) {
            return new ExportResult(0, 0, 0);
        }
        Instant now = clock.instant();
        outbox.purgeTerminalBefore(now.minus(properties.getRetention()));
        Path dir = Path.of(properties.getExportDir());
        List<JdbcWaitlistOpsNotificationOutbox.OutboxRow> due = outbox.findDue(now, properties.getBatchSize());
        int exported = 0;
        int retried = 0;
        int failed = 0;
        for (JdbcWaitlistOpsNotificationOutbox.OutboxRow row : due) {
            String category = writeEnvelope(dir, row);
            if (category == null) {
                outbox.markExported(row.id(), clock.instant());
                outbox.count("exported");
                exported++;
                continue;
            }
            int attempts = row.attempts() + 1;
            if (attempts >= properties.getMaxExportAttempts()) {
                outbox.markFailed(row.id(), attempts, category);
                outbox.count("export_failed");
                log.warn("Waitlist ops notification export gave up; category={}, attempts={}", category, attempts);
                failed++;
            } else {
                outbox.markRetry(row.id(), attempts, now.plus(backoff(attempts)), category);
                outbox.count("export_retry");
                retried++;
            }
        }
        return new ExportResult(exported, retried, failed);
    }

    Duration backoff(int attempts) {
        long factor = 1L << Math.min(attempts - 1, 20);
        Duration delay = properties.getRetryBaseDelay().multipliedBy(factor);
        return delay.compareTo(properties.getRetryMaxDelay()) > 0 ? properties.getRetryMaxDelay() : delay;
    }

    /** @return null on success, otherwise a bounded error category */
    private String writeEnvelope(Path dir, JdbcWaitlistOpsNotificationOutbox.OutboxRow row) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(envelope(row));
        } catch (JsonProcessingException ex) {
            return "serialization_error";
        }
        String name = "waitlist-" + row.id() + ".json";
        Path target = dir.resolve(name);
        // Dot-prefixed temp name never matches the relay's *.json glob.
        Path temp = dir.resolve("." + name + ".tmp");
        try {
            Files.write(temp, json);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        } catch (NoSuchFileException ex) {
            return "export_dir_missing";
        } catch (IOException ex) {
            return "export_io_error";
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private Map<String, Object> envelope(JdbcWaitlistOpsNotificationOutbox.OutboxRow row) {
        // Allow-listed fields only. The relay rejects any additional key.
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("contractVersion", CONTRACT_VERSION);
        envelope.put("eventId", row.id().toString());
        envelope.put("eventType", row.eventType());
        envelope.put("occurredAt", row.occurredAt().truncatedTo(ChronoUnit.SECONDS).toString());
        envelope.put("environment", properties.getEnvironment());
        envelope.put("producer", PRODUCER);
        envelope.put("dedupKey", row.dedupKey());
        return envelope;
    }

    public record ExportResult(int exported, int retried, int failed) {
    }
}
