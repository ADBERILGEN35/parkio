plugins {
    id("parkio.spring-service")
}

description = "API gateway and edge routing for all Parkio services"

dependencies {
    implementation(libs.spring.cloud.gateway)
    implementation(libs.spring.boot.starter.actuator)
    // Prometheus metrics export: /actuator/prometheus (scraped by docker/prometheus).
    runtimeOnly(libs.micrometer.registry.prometheus)
    // Distributed tracing: export OTLP spans to Tempo (Micrometer Observation -> OpenTelemetry).
    implementation(libs.micrometer.tracing.bridge.otel)
    runtimeOnly(libs.opentelemetry.exporter.otlp)
    // Reactive Redis backs the edge RequestRateLimiter (token bucket per user/IP).
    implementation(libs.spring.boot.starter.data.redis.reactive)
    // Minimal waitlist persistence uses Flyway-owned schema and JdbcTemplate.
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    // Bean Validation for @ConfigurationProperties (fail-closed JWT secret check).
    implementation(libs.spring.boot.starter.validation)

    // RS256 JWT parsing after public-key resolution from auth-service JWKS.
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    runtimeOnly(libs.postgresql)

    // spring-cloud-starter hardcodes bcprov 1.80.2; raise floor to catalog ≥1.85 (CVE-2026-8763).
    constraints {
        implementation(libs.bouncycastle.bcprov) {
            because("CVE-2026-8763: bcprov-jdk18on before 1.85 is blocked by Security CI CRITICAL policy")
        }
    }

    testImplementation(libs.spring.boot.starter.test)
    // Real-PostgreSQL proof of waitlist migrations/transactions (`integrationTest`, Docker).
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
