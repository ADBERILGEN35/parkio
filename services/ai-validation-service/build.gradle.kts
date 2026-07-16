plugins {
    id("parkio.spring-service")
}

description = "AI-assisted validation of submissions"

dependencyManagement {
    imports {
        mavenBom("io.github.resilience4j:resilience4j-bom:${libs.versions.resilience4j.get()}")
    }
}

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

    // Circuit breaker for outbound Gemini calls (mirrors parking geocoding).
    implementation(libs.spring.boot.starter.aop)
    implementation(libs.resilience4j.spring.boot3)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
