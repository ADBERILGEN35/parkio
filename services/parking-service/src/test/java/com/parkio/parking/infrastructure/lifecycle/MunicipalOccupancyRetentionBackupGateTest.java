package com.parkio.parking.infrastructure.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MunicipalOccupancyRetentionBackupGateTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    @TempDir
    Path temp;

    @Test
    void acceptsRecentCompleteStamp() throws Exception {
        Path run = temp.resolve("2026-09-18T03-30-01Z");
        Files.createDirectories(run);
        Files.writeString(run.resolve("COMPLETE"), "ok");
        MunicipalOccupancyRetentionBackupGate gate = new MunicipalOccupancyRetentionBackupGate(
                temp, Duration.ofHours(36), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(gate.denyReason()).isNull();
    }

    @Test
    void deniesWhenCompleteMissing() {
        MunicipalOccupancyRetentionBackupGate gate = new MunicipalOccupancyRetentionBackupGate(
                temp, Duration.ofHours(36), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(gate.denyReason()).isEqualTo("backup_complete_missing");
    }

    @Test
    void deniesWhenStampDirMissing() {
        MunicipalOccupancyRetentionBackupGate gate = new MunicipalOccupancyRetentionBackupGate(
                temp.resolve("missing"), Duration.ofHours(36), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(gate.denyReason()).isEqualTo("backup_stamp_dir_missing");
    }
}
