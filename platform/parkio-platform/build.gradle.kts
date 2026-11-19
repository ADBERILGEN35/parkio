import java.util.concurrent.TimeUnit

plugins {
    `java-library`
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

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    // Jackson 2 stays pinned to the patched release (jacksonBomVersion). Services pin it through the
    // dependency-management plugin (jackson-2-bom.version in Spring Boot 4); this keeps the platform's
    // own classpaths on the same release.
    api(platform("com.fasterxml.jackson:jackson-bom:${project.property("jacksonBomVersion")}"))
    api(libs.kafka.clients)
    constraints {
        // kafka-clients 4.2.2 pins lz4-java 1.11.2 (runtime): CVE-2026-106451 (HIGH) and five lower lz4-java
        // CVEs are fixed in 1.11.4. Every service gets kafka-clients through this platform, so this one
        // constraint moves all of them. Drop it once kafka-clients itself requires >= 1.11.4.
        api("at.yawk.lz4:lz4-java:1.11.4")
        // kafka-clients 4.2.2 pins snappy-java 1.1.10.7 (runtime): CVE-2026-108106 and CVE-2026-93451 (both HIGH, #501)
        // are fixed in 1.1.10.9. Drop it once kafka-clients itself requires >= 1.1.10.9.
        api("org.xerial.snappy:snappy-java:1.1.10.9")
        // The GCS client (auth) and the MinIO client (media) pull guava 33.5.0/33.4.8: CVE-2026-102554 (HIGH, #501)
        // is fixed in 33.7.2. A constraint adds no dependency to the services that have no guava.
        api("com.google.guava:guava:33.7.2-jre")
    }
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    api("io.opentelemetry:opentelemetry-api")
    api("io.opentelemetry:opentelemetry-context")

    implementation("org.slf4j:slf4j-api")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // Lz4CompressionCompatibilityTest compiles against lz4-java (a runtime-only dependency of kafka-clients);
    // the version comes from the constraint above.
    testImplementation("at.yawk.lz4:lz4-java")
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs @Tag(\"integration\") Testcontainers integration tests (requires Docker)."
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter(tasks.named("test"))

    val requireDocker = providers.gradleProperty("parkio.integrationTest.requireDocker")
        .map(String::toBoolean).orElse(false)
    inputs.property("requireDocker", requireDocker)
    // With requireDocker the run itself is the evidence: a build-cache restore or an up-to-date skip
    // would bypass the Docker check below and report tests that never ran (#205 review B1).
    outputs.doNotCacheIf("parkio.integrationTest.requireDocker=true: the tests must execute") {
        requireDocker.get()
    }
    outputs.upToDateWhen { !requireDocker.get() }
    doFirst {
        if (requireDocker.get()) {
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
                    "parkio.integrationTest.requireDocker=true but no Docker daemon is reachable.",
                )
            }
        }
    }
}
