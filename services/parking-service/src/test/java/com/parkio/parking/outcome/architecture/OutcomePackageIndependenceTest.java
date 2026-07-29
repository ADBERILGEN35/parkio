package com.parkio.parking.outcome.architecture;

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

class OutcomePackageIndependenceTest {

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
    void outcomeSourcesDoNotDependOnFrameworkOrInfraPackages() throws IOException {
        Path root = locateOutcomeSources();
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
        assertThat(violations).isEmpty();
    }

    @Test
    void outcomePortInterfacesExistAsTypes() {
        assertThat(com.parkio.parking.outcome.port.OutcomeHistoryPort.class.isInterface()).isTrue();
        assertThat(com.parkio.parking.outcome.port.OutcomeTrustConsumerPort.class.isInterface()).isTrue();
        assertThat(com.parkio.parking.outcome.policy.OutcomePolicy.class.isInterface()).isTrue();
    }

    private static Path locateOutcomeSources() {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = new Path[] {
            cwd.resolve("src/main/java/com/parkio/parking/outcome"),
            cwd.resolve("services/parking-service/src/main/java/com/parkio/parking/outcome")
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        Path probe = cwd;
        for (int i = 0; i < 6; i++) {
            Path nested = probe.resolve("services/parking-service/src/main/java/com/parkio/parking/outcome");
            if (Files.isDirectory(nested)) {
                return nested;
            }
            probe = probe.getParent();
            if (probe == null) {
                break;
            }
        }
        throw new IllegalStateException("Cannot locate outcome sources from " + cwd + " locale=" + Locale.getDefault());
    }
}