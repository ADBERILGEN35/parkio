/**
 * Shared build conventions for every Parkio Spring Boot service.
 *
 * Applies the Java toolchain, the Spring Boot and dependency-management plugins,
 * and imports the Spring Cloud BOM so individual services only declare the
 * starters they need.
 */
import java.util.concurrent.TimeUnit
import org.cyclonedx.gradle.CyclonedxAggregateTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component

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
val jacksonBomVersion: String = project.findProperty("jacksonBomVersion") as String

// Pin Jackson 2 centrally so every service image resolves the same patched artifacts. In Spring Boot 4
// `jackson-bom.version` is the Jackson 3 BOM; Jackson 2 is `jackson-2-bom.version`.
extra["jackson-2-bom.version"] = jacksonBomVersion

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
// service, no XML+JSON duplication. Plugin 3.x splits the work: `cyclonedxDirectBom` describes
// this project and `cyclonedxBom` aggregates it (each task is configured on its own).
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs = listOf("runtimeClasspath")
    projectType = Component.Type.APPLICATION
    schemaVersion = org.cyclonedx.Version.VERSION_15
    includeBomSerialNumber = true
    includeLicenseText = false
    jsonOutput = layout.buildDirectory.file("reports/cyclonedx-direct/bom.json")
    xmlOutput.convention(null as RegularFile?)
}
tasks.named<CyclonedxAggregateTask>("cyclonedxBom") {
    projectType = Component.Type.APPLICATION
    schemaVersion = org.cyclonedx.Version.VERSION_15
    includeBomSerialNumber = true
    includeLicenseText = false
    jsonOutput = layout.buildDirectory.file("reports/sbom/bom.json")
    xmlOutput.convention(null as RegularFile?)
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
//    Docker is unavailable instead of silently skipping every suite and reporting a false green,
//    and it always executes: it neither restores a result from the build cache nor skips as up to
//    date, because either would bypass that check and report tests that never ran.
tasks.register<Test>("integrationTest") {
    // N-19: one fork under Docker contention so parallel Testcontainers do not OOM/kill peers.
    maxParallelForks = 1
    // Each IT class has its own Testcontainers JDBC URL, so another class can never reuse its
    // ApplicationContext. The default cache (32) still keeps those contexts, and their pools,
    // after the container has stopped. Parking's single worker reached HikariPool-30 and the
    // next refresh died with Java heap space while creating KafkaAnnotationDrivenConfiguration
    // (run 38872692932, RoadsideParkingControllerTimestamptzIT). One slot preserves reuse
    // across methods of the same class and closes the previous class's context first.
    systemProperty("spring.test.context.cache.maxSize", "1")
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
    // With requireDocker the run itself is the evidence (#205 review B1).
    outputs.doNotCacheIf("parkio.integrationTest.requireDocker=true: the tests must execute") {
        requireDocker.get()
    }
    outputs.upToDateWhen { !requireDocker.get() }
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
