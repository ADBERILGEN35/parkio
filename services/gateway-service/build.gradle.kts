plugins {
    id("parkio.spring-service")
}

description = "API gateway and edge routing for all Parkio services"

dependencies {
    implementation(libs.spring.cloud.gateway)
    implementation(libs.spring.boot.starter.actuator)
    // Reactive Redis backs the edge RequestRateLimiter (token bucket per user/IP).
    implementation(libs.spring.boot.starter.data.redis.reactive)
    // Bean Validation for @ConfigurationProperties (fail-closed JWT secret check).
    implementation(libs.spring.boot.starter.validation)

    // JWT validation at the edge. Same HS256 contract as auth-service.
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
