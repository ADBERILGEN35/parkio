plugins {
    id("parkio.spring-service")
}

description = "Upload and serving of images and other media"

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.spring.boot.starter.actuator)
    // Prometheus metrics export: /actuator/prometheus (scraped by docker/prometheus).
    runtimeOnly(libs.micrometer.registry.prometheus)
    // Distributed tracing: export OTLP spans to Tempo (Micrometer Observation -> OpenTelemetry).
    implementation(libs.micrometer.tracing.bridge.otel)
    runtimeOnly(libs.opentelemetry.exporter.otlp)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)

    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    // Asynchronous event transport (Kafka). Topic provisioning + config now;
    // outbox relay and consumers are added later.
    implementation(libs.spring.kafka)

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
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
