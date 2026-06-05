plugins {
    id("parkio.spring-service")
}

description = "API gateway and edge routing for all Parkio services"

dependencies {
    implementation(libs.spring.cloud.gateway)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
