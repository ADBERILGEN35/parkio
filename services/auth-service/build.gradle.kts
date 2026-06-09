plugins {
    id("parkio.spring-service")
}

description = "Authentication, authorization and token issuance"

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.spring.boot.starter.actuator)
    // Prometheus metrics export: /actuator/prometheus (scraped by docker/prometheus).
    runtimeOnly(libs.micrometer.registry.prometheus)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)

    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    // Asynchronous event transport (Kafka). Topic provisioning + config now;
    // outbox relay and consumers are added later.
    implementation(libs.spring.kafka)

    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    // Kafka integration tests (Testcontainers) — only run via the `integrationTest` task.
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
