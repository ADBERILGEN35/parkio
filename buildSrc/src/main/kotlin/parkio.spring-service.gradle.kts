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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
