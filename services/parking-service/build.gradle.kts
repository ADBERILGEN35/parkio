plugins {
    id("parkio.spring-service")
}

description = "Parking spots, availability and reservations"

dependencyManagement {
    imports {
        // Spring Cloud's BOM manages Resilience4j 2.2.x transitives. Keep the
        // Spring Boot 3 integration and its Spring 6/fallback modules on one line.
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

    // Redis: best-effort cache for forward-geocoding lookups (positive 24h / negative 5m).
    // The same Redis the gateway uses; parking-service is already wired to it in compose.
    implementation(libs.spring.boot.starter.data.redis)
    // Resilience for the outbound Nominatim geocoding call: circuit breaker, bulkhead and an
    // outbound rate limiter (respects the provider usage policy). AOP backs the annotations.
    implementation(libs.spring.boot.starter.aop)
    implementation(libs.resilience4j.spring.boot3)

    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    // Asynchronous event transport (Kafka). Topic provisioning + config now;
    // outbox relay and consumers are added later.
    implementation(libs.spring.kafka)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    // Kafka integration tests (Testcontainers) — only run via the `integrationTest` task.
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
