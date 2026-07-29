package com.parkio.parking.availability.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Structural guardrail: core availability sources must not import framework / infra types.
 */
class AvailabilityPackageIndependenceTest {

    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "org.springframework",
            "jakarta.persistence",
            "jakarta.servlet",
            "org.apache.kafka",
            "org.springframework.kafka",
            "com.parkio.parking.infrastructure",
            "com.parkio.parking.presentation",
            "com.parkio.parking.application");

    @Test
    void availabilitySourcesDoNotDependOnFrameworkOrInfraPackages() throws IOException {
        Path root = locateAvailabilitySources();
        assertThat(root).exists();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = Files.readString(path, StandardCharsets.UTF_8);
                    for (String line : source.split("\\R")) {
                        String trimmed = line.trim();
                        if (!trimmed.startsWith("import ")) {
                            continue;
                        }
                        String imported = trimmed.substring("import ".length()).replace(";", "").trim();
                        if (imported.startsWith("com.parkio.parking.domain")) {
                            continue;
                        }
                        for (String forbidden : FORBIDDEN_PREFIXES) {
                            if (imported.equals(forbidden) || imported.startsWith(forbidden + ".")) {
                                violations.add(path.getFileName() + " -> " + imported);
                            }
                        }
                    }
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }

        assertThat(violations)
                .as("forbidden imports in availability package: %s", violations)
                .isEmpty();
    }

    @Test
    void availabilityPortInterfacesExistAsTypes() {
        assertThat(com.parkio.parking.availability.port.AvailabilityHistoryPort.class.isInterface()).isTrue();
        assertThat(com.parkio.parking.availability.policy.AvailabilityPolicy.class.isInterface()).isTrue();
    }

    private static Path locateAvailabilitySources() {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = new Path[] {
            cwd.resolve("src/main/java/com/parkio/parking/availability"),
            cwd.resolve("services/parking-service/src/main/java/com/parkio/parking/availability")
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        Path probe = cwd;
        for (int i = 0; i < 6; i++) {
            Path nested = probe.resolve("services/parking-service/src/main/java/com/parkio/parking/availability");
            if (Files.isDirectory(nested)) {
                return nested;
            }
            probe = probe.getParent();
            if (probe == null) {
                break;
            }
        }
        throw new IllegalStateException(
                "Cannot locate availability sources from " + cwd.toAbsolutePath()
                        + " locale=" + Locale.getDefault());
    }
}
