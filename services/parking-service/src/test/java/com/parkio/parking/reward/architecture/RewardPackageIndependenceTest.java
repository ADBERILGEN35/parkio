package com.parkio.parking.reward.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RewardPackageIndependenceTest {

    @Test
    void rewardPackageDoesNotImportFrameworkOrInfrastructureTypes() throws IOException {
        Path root = Path.of("src/main/java/com/parkio/parking/reward").toAbsolutePath();
        List<String> disallowed = List.of(
                "org.springframework",
                "jakarta.persistence",
                "org.apache.kafka",
                "io.micrometer",
                "com.parkio.parking.infrastructure",
                "com.parkio.gamification");

        try (var stream = Files.walk(root)) {
            stream.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    for (String prefix : disallowed) {
                        assertThat(content)
                                .as(path + " must not import " + prefix)
                                .doesNotContain("import " + prefix);
                    }
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
    }
}
