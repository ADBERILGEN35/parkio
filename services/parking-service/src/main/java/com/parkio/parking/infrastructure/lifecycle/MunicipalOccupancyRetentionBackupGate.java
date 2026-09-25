package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.MunicipalOccupancyRetentionService;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fail-closed backup gate: requires a recent {@code COMPLETE} stamp under the
 * configured backup root (host path mounted read-only into the container).
 */
public final class MunicipalOccupancyRetentionBackupGate
        implements MunicipalOccupancyRetentionService.BackupGate {

    private static final Logger log = LoggerFactory.getLogger(MunicipalOccupancyRetentionBackupGate.class);

    private final Path stampDir;
    private final Duration maxAge;
    private final Clock clock;

    public MunicipalOccupancyRetentionBackupGate(Path stampDir, Duration maxAge, Clock clock) {
        this.stampDir = stampDir;
        this.maxAge = maxAge == null ? Duration.ofHours(36) : maxAge;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public String denyReason() {
        if (stampDir == null || stampDir.toString().isBlank()) {
            return "backup_stamp_dir_unset";
        }
        if (!Files.isDirectory(stampDir)) {
            return "backup_stamp_dir_missing";
        }
        Instant newest = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(stampDir)) {
            for (Path child : stream) {
                Path complete = child.resolve("COMPLETE");
                if (!Files.isRegularFile(complete)) {
                    continue;
                }
                FileTime mtime = Files.getLastModifiedTime(complete);
                Instant at = mtime.toInstant();
                if (newest == null || at.isAfter(newest)) {
                    newest = at;
                }
            }
        } catch (Exception ex) {
            log.warn("municipal occupancy retention backup gate failed to scan stamps");
            return "backup_stamp_scan_failed";
        }
        if (newest == null) {
            return "backup_complete_missing";
        }
        Instant cutoff = clock.instant().minus(maxAge);
        if (newest.isBefore(cutoff)) {
            return "backup_complete_stale";
        }
        return null;
    }
}
