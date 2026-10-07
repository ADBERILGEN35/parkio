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

    // S3-compatible object-lock store for durable erasure records (off by default).
    implementation(libs.minio)

    // MinIO pulls bcprov transitively; raise floor to catalog ≥1.85 (CVE-2026-8763).
    constraints {
        implementation(libs.bouncycastle.bcprov) {
            because("CVE-2026-8763: bcprov-jdk18on before 1.85 is blocked by Security CI CRITICAL policy")
        }
    }

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

// Print a failed integration test's assertion message in the build log. Gradle's default SHORT
// format shows only the exception class and line, which hid the measured medians of
// AccountExistenceTimingPostgresIT when it failed in CI (CL-F14.2).
tasks.named<Test>("integrationTest") {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// U02 recovery drill tool (scripts/recovery-drill.sh; coordinator decision D2). It runs from the
// test classpath only, so it is never packaged in the service image and adds no production entry
// point: it publishes one checkpoint with the real producer and exports the evidence store as a
// bundle, both against the disposable drill environment. Settings come from DRILL_* variables.
val recoveryDrillToolchain = extensions.getByType<JavaPluginExtension>().toolchain
tasks.register<JavaExec>("recoveryDrillTool") {
    group = "verification"
    description = "U02 recovery drill tool (disposable drill environment only; never shipped)"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.parkio.auth.infrastructure.durable.RecoveryDrillTool")
    javaLauncher.set(javaToolchains.launcherFor(recoveryDrillToolchain))
}
