plugins {
    id("parkio.spring-service")
}

description = "API gateway and edge routing for all Parkio services"

dependencies {
    implementation(libs.spring.cloud.gateway)
    implementation(libs.spring.boot.starter.actuator)
    // Prometheus metrics export: /actuator/prometheus (scraped by docker/prometheus).
    runtimeOnly(libs.micrometer.registry.prometheus)
    // Reactive Redis backs the edge RequestRateLimiter (token bucket per user/IP).
    implementation(libs.spring.boot.starter.data.redis.reactive)
    // Bean Validation for @ConfigurationProperties (fail-closed JWT secret check).
    implementation(libs.spring.boot.starter.validation)

    // RS256 JWT parsing after public-key resolution from auth-service JWKS.
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
