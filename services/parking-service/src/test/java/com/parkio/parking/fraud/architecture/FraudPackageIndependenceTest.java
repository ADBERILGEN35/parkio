package com.parkio.parking.fraud.architecture;

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

class FraudPackageIndependenceTest {

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
    void fraudSourcesDoNotDependOnFrameworkOrInfraPackages() throws IOException {
        Path root = locateFraudSources();
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

    private static Path locateFraudSources() {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = new Path[] {
            cwd.resolve("src/main/java/com/parkio/parking/fraud"),
            cwd.resolve("services/parking-service/src/main/java/com/parkio/parking/fraud")
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate fraud sources from " + cwd + " locale=" + Locale.getDefault());
    }
}
