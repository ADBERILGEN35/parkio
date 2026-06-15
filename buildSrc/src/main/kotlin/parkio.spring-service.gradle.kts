/**
 * Shared build conventions for every Parkio Spring Boot service.
 *
 * Applies the Java toolchain, the Spring Boot and dependency-management plugins,
 * and imports the Spring Cloud BOM so individual services only declare the
 * starters they need.
 */
import java.util.concurrent.TimeUnit

plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.parkio"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val springCloudVersion: String = project.findProperty("springCloudVersion") as String

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion")
    }
}

// Default `test` (and therefore `check`/`build`) runs unit tests only and excludes the
// `integration` tag, so the standard build never requires Docker.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Opt-in Testcontainers integration tests: `./gradlew integrationTest` (requires Docker).
// Deliberately NOT wired into `check`/`build`. Shares the `test` source set.
//
// Docker handling:
//  - Locally (default): each suite is `@Testcontainers(disabledWithoutDocker = true)`, so the
//    tests are discovered and *skipped* (not failed) when no Docker daemon is reachable.
//  - In CI: pass `-Pparkio.integrationTest.requireDocker=true`. The task then fails fast when
//    Docker is unavailable instead of silently skipping every suite and reporting a false green.
tasks.register<Test>("integrationTest") {
    description = "Runs @Tag(\"integration\") Testcontainers integration tests (requires Docker)."
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter(tasks.named("test"))

    // Read via `providers` so the action stays configuration-cache compatible (no script-object
    // capture). The Docker probe runs at execution time so it reflects the runner, not config time.
    val requireDocker = providers.gradleProperty("parkio.integrationTest.requireDocker")
        .map(String::toBoolean).orElse(false)
    inputs.property("requireDocker", requireDocker)
    doFirst {
        if (requireDocker.get()) {
            // Probe the daemon with `docker info`, bounded so a hung daemon can't stall the build.
            // Deterministic readiness check (exit code), not a timing-based sleep.
            val available = try {
                val process = ProcessBuilder("docker", "info")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                if (process.waitFor(60, TimeUnit.SECONDS)) {
                    process.exitValue() == 0
                } else {
                    process.destroyForcibly()
                    false
                }
            } catch (ex: Exception) {
                false
            }
            if (!available) {
                throw GradleException(
                    "parkio.integrationTest.requireDocker=true but no Docker daemon is reachable. " +
                        "Integration tests need Docker (PostgreSQL/Kafka/MinIO via Testcontainers). " +
                        "Start Docker and retry, or drop the flag locally to skip container tests gracefully.",
                )
            }
        }
    }
}
