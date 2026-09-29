plugins {
    id("parkio.spring-service")
}

description = "Authentication, authorization and token issuance"

// Disposable CSRF-lab instrumentation only. Never packaged into bootJar / images.
sourceSets {
    create("csrfLab") {
        compileClasspath += sourceSets["main"].output + configurations["compileClasspath"]
        runtimeClasspath += output + compileClasspath
    }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    if (project.findProperty("parkio.csrfLab") == "true") {
        dependsOn("compileCsrfLabJava")
        classpath += sourceSets["csrfLab"].output
    }
}

tasks.register("assertCsrfLabAbsentFromBootJar") {
    group = "verification"
    description = "Fail if CsrfLabRequestCaptureFilter is packaged into the production bootJar"
    dependsOn("bootJar")
    doLast {
        val jarFile = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
            .get()
            .archiveFile
            .get()
            .asFile
        var hit = false
        zipTree(jarFile).visit {
            if (path.contains("CsrfLabRequestCaptureFilter")) {
                hit = true
            }
        }
        if (hit) {
            throw GradleException(
                "CsrfLabRequestCaptureFilter must not ship in ${jarFile.name} (lab-only source set)",
            )
        }
        logger.lifecycle("Verified CsrfLabRequestCaptureFilter absent from ${jarFile.name}")
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
    implementation(libs.spring.boot.starter.data.redis)
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
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
