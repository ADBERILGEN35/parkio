/**
 * Shared build conventions for every Parkio Spring Boot service.
 *
 * Applies the Java toolchain, the Spring Boot and dependency-management plugins,
 * and imports the Spring Cloud BOM so individual services only declare the
 * starters they need.
 */
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
tasks.register<Test>("integrationTest") {
    description = "Runs @Tag(\"integration\") Testcontainers integration tests (requires Docker)."
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}
