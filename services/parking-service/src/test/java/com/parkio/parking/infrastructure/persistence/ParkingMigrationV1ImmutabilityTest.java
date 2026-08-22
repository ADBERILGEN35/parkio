package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

/**
 * PROD-DEPLOY-01A-R8.5 — {@code V1__enable_postgis.sql} is frozen.
 *
 * <p>V1 has already been applied on every environment whose database identity may install the
 * extension (hosted-beta, local, CI), and Flyway recorded its checksum in their
 * {@code flyway_schema_history}. Flyway re-derives that checksum from the file on every startup and
 * fails {@code validate()} when it moves, so editing V1 — even to make it more idempotent, even to
 * make it work on Azure — would take those environments down on their next boot.
 *
 * <p>The managed-DB fix is therefore a baseline that skips V1, never a rewrite of it. This test
 * pins both the bytes and the Flyway checksum so a well-meaning edit fails here, in a Docker-free
 * unit test, rather than in an environment's startup.
 *
 * <p>{@code ManagedParkingFlywayBaselineIT.stateE_*} asserts the same checksum value as read back
 * out of a real Flyway history table, which is what proves {@link #flywayChecksum} reproduces
 * Flyway's own algorithm rather than merely being self-consistent.
 */
class ParkingMigrationV1ImmutabilityTest {

    static final String V1_RESOURCE = "db/migration/V1__enable_postgis.sql";

    /** SHA-256 over the exact file bytes. */
    static final String V1_SHA256 =
            "962ca38a3dc7cb78ed80c4b1ff96b352c6f4c62c37efc333c4e868559a2a531b";

    /** The value Flyway records in {@code flyway_schema_history.checksum} for V1. */
    static final int V1_FLYWAY_CHECKSUM = -653528947;

    @Test
    void v1FileBytesAreUnchanged() throws Exception {
        assertThat(sha256(readBytes()))
                .as("V1__enable_postgis.sql is frozen: environments that already applied it would"
                        + " fail Flyway validate() on their next start if its bytes moved")
                .isEqualTo(V1_SHA256);
    }

    @Test
    void v1FlywayChecksumIsUnchanged() throws Exception {
        assertThat(flywayChecksum(readBytes()))
                .as("recorded in existing flyway_schema_history rows; a change breaks validate()")
                .isEqualTo(V1_FLYWAY_CHECKSUM);
    }

    @Test
    void v1StillEnablesPostgisIdempotently() throws Exception {
        String sql = new String(readBytes(), StandardCharsets.UTF_8);
        assertThat(sql)
                .as("the owner path still relies on V1 actually creating the extension")
                .contains("CREATE EXTENSION IF NOT EXISTS postgis;");
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] readBytes() throws IOException {
        try (InputStream in = ParkingMigrationV1ImmutabilityTest.class.getClassLoader()
                .getResourceAsStream(V1_RESOURCE)) {
            assertThat(in).as("%s must be on the classpath", V1_RESOURCE).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * Flyway's SQL migration checksum: CRC32 over each line's UTF-8 bytes with the line terminator
     * excluded, so CRLF and LF checkouts agree. Reimplemented here because Flyway's own
     * {@code ChecksumCalculator} is an internal API.
     */
    static int flywayChecksum(byte[] bytes) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        CRC32 crc32 = new CRC32();
        for (String line : lines) {
            crc32.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return (int) crc32.getValue();
    }
}
