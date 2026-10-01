package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks an unconstrained Flyway migrate against the versioned scripts on the classpath.
 * An unconstrained migrate applies the current head, which moved from 26 to 29 when
 * V27-V29 were added. Pinning that head in each shadow test hid a missing or failed
 * script only until the next migration. The script set must stay contiguous from 1,
 * and every successful history row must match it.
 */
public final class ParkingMigrationHistory {

    private static final Pattern VERSIONED_SCRIPT = Pattern.compile("V(\\d+)__.+\\.sql");

    private ParkingMigrationHistory() {}

    public static void assertSuccessfulVersionsMatchClasspath(Connection connection) throws SQLException, IOException {
        SortedSet<String> scripts = classpathVersions();
        assertContiguousFromOne(scripts);
        assertThat(successfulVersions(connection)).isEqualTo(scripts);
        assertThat(latestSuccessfulVersion(connection)).isEqualTo(scripts.last());
    }

    static SortedSet<String> classpathVersions() throws IOException {
        SortedSet<String> versions = new TreeSet<>(java.util.Comparator.comparingInt(Integer::parseInt));
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> roots = loader.getResources("db/migration");
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            if (!"file".equals(root.getProtocol())) {
                continue;
            }
            Path directory;
            try {
                directory = Path.of(root.toURI());
            } catch (URISyntaxException ex) {
                throw new IOException("Cannot read migration directory " + root, ex);
            }
            try (var files = Files.list(directory)) {
                files.map(path -> path.getFileName().toString()).forEach(name -> {
                    Matcher matcher = VERSIONED_SCRIPT.matcher(name);
                    if (matcher.matches()) {
                        versions.add(matcher.group(1));
                    }
                });
            }
        }
        return Collections.unmodifiableSortedSet(versions);
    }

    private static void assertContiguousFromOne(SortedSet<String> versions) {
        assertThat(versions).isNotEmpty();
        int head = Integer.parseInt(versions.last());
        assertThat(versions).hasSize(head);
        for (int version = 1; version <= head; version++) {
            assertThat(versions).contains(Integer.toString(version));
        }
    }

    private static SortedSet<String> successfulVersions(Connection connection) throws SQLException {
        SortedSet<String> versions = new TreeSet<>(java.util.Comparator.comparingInt(Integer::parseInt));
        try (var statement = connection.prepareStatement(
                        "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                versions.add(result.getString(1));
            }
        }
        return versions;
    }

    private static String latestSuccessfulVersion(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
