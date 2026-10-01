package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ParkingMigrationHistoryTest {

    @Test
    void versionedScriptsStayContiguousThroughTheMigrationsAfterTheShadowLedgers() throws IOException {
        var versions = ParkingMigrationHistory.classpathVersions();

        assertThat(versions).contains("23", "24", "25", "26", "27", "28", "29");
        assertThat(versions).hasSize(Integer.parseInt(versions.last()));
        for (int version = 1; version <= Integer.parseInt(versions.last()); version++) {
            assertThat(versions).contains(Integer.toString(version));
        }
    }
}
