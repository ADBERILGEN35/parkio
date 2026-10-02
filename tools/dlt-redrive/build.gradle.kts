import java.util.concurrent.TimeUnit

plugins {
    java
    application
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
    implementation(libs.kafka.clients)
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    testImplementation(libs.spring.boot.starter.test)
    // Disposable-Kafka replay tests (`integrationTest` only).
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "com.parkio.tools.dltredrive.KafkaDltRedriveTool"
}

// Same split as the services (buildSrc parkio.spring-service): `test` never needs Docker,
// `integrationTest` runs the @Tag("integration") Testcontainers suites.
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
