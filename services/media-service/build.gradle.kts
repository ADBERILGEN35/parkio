plugins {
    id("parkio.spring-service")
}

description = "Upload and serving of images and other media"

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.jackson2)
    // Prometheus metrics export: /actuator/prometheus (scraped by docker/prometheus).
    runtimeOnly(libs.micrometer.registry.prometheus)
    // Distributed tracing: export OTLP spans to Tempo (Micrometer Observation -> OpenTelemetry).
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.spring.boot.micrometer.tracing.opentelemetry)
    implementation(libs.spring.boot.opentelemetry)
    runtimeOnly(libs.opentelemetry.exporter.otlp)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)

    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)

    // Asynchronous event transport (Kafka). Topic provisioning + config now;
    // outbox relay and consumers are added later.
    implementation(libs.spring.boot.starter.kafka)

    // S3-compatible object storage.
    implementation(libs.minio)

    // MinIO pulls bcprov transitively; raise floor to catalog ≥1.85 (CVE-2026-8763).
    constraints {
        implementation(libs.bouncycastle.bcprov) {
            because("CVE-2026-8763: bcprov-jdk18on before 1.85 is blocked by Security CI CRITICAL policy")
        }
    }

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
