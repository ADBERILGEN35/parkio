plugins {
    id("parkio.spring-service")
}

description = "Push, email and in-app notifications"

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.spring.boot.starter.actuator)
    // Prometheus metrics export: /actuator/prometheus (scraped by docker/prometheus).
    runtimeOnly(libs.micrometer.registry.prometheus)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)

    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    // Asynchronous event transport (Kafka). Topic provisioning + config now;
    // outbox relay and consumers are added later.
    implementation(libs.spring.kafka)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
