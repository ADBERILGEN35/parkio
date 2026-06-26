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
    // Supply chain: emit a CycloneDX SBOM of the service's resolved runtime classpath.
    id("org.cyclonedx.bom")
}

group = "com.parkio"
// Release version is injected by CI (`-Pversion=…` / the release workflow); the
// SNAPSHOT default keeps local/dev builds and the SBOM componentVersion honest.
version = (project.findProperty("parkioVersion") as String?)?.takeIf { it.isNotBlank() }
    ?: "0.0.1-SNAPSHOT"

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

dependencies {
    "implementation"(project(":platform:parkio-platform"))
}

// Reproducible archives: zero embedded timestamps and use a stable file order so the
// bootJar is byte-for-byte deterministic given the same inputs (supply-chain integrity).
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// CycloneDX SBOM: deterministic, single-format (JSON) bill of materials of the service's
// RUNTIME classpath only — the dependency set that actually ships in the bootJar / image.
// Produced on demand by `:services:<svc>:cyclonedxBom` (the release workflow collects them);
// deliberately NOT wired into `build`, so normal CI stays fast and Docker-free. One file per
// service, no XML+JSON duplication.
tasks.named<org.cyclonedx.gradle.CycloneDxTask>("cyclonedxBom") {
    setIncludeConfigs(listOf("runtimeClasspath"))
    setProjectType("application")
    setSchemaVersion("1.5")
    setDestination(layout.buildDirectory.dir("reports/sbom").get().asFile)
    setOutputName("bom")
    setOutputFormat("json")
    setIncludeBomSerialNumber(true)
    setIncludeLicenseText(false)
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
