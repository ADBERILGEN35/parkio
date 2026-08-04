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
    api(libs.kafka.clients)
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
