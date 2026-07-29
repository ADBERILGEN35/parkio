package com.parkio.parking.trust.architecture;

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

class TrustPackageIndependenceTest {

    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "org.springframework",
            "jakarta.persistence",
            "jakarta.servlet",
            "org.apache.kafka",
            "org.springframework.kafka",
            "io.micrometer",
            "com.parkio.parking.infrastructure",
            "com.parkio.parking.presentation",
            "com.parkio.parking.application");

    @Test
    void trustSourcesDoNotDependOnFrameworkOrInfraPackages() throws IOException {
        Path root = locateTrustSources();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = Files.readString(path, StandardCharsets.UTF_8);
                    for (String line : source.split("\\R")) {
                        String trimmed = line.trim();
                        if (!trimmed.startsWith("import ")) {
                            continue;
                        }
                        String imported = trimmed.substring("import ".length()).replace(";", "").trim();
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
        assertThat(violations).isEmpty();
    }

    private static Path locateTrustSources() {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = new Path[] {
            cwd.resolve("src/main/java/com/parkio/parking/trust"),
            cwd.resolve("services/parking-service/src/main/java/com/parkio/parking/trust")
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        Path probe = cwd;
        for (int i = 0; i < 6; i++) {
            Path nested = probe.resolve("services/parking-service/src/main/java/com/parkio/parking/trust");
            if (Files.isDirectory(nested)) {
                return nested;
            }
            probe = probe.getParent();
            if (probe == null) {
                break;
            }
        }
        throw new IllegalStateException(
                "Cannot locate trust sources from " + cwd.toAbsolutePath() + " locale=" + Locale.getDefault());
    }
}
