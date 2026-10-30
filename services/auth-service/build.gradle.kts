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
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)

    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)

    // Asynchronous event transport (Kafka). Topic provisioning + config now;
    // outbox relay and consumers are added later.
    implementation(libs.spring.boot.starter.kafka)

    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // S3-compatible object-lock store for durable erasure records (off by default).
    implementation(libs.minio)
    // GCS Object Retention Lock adapter for durable erasure records (off by default).
    implementation(libs.google.cloud.storage)

    // MinIO pulls bcprov transitively; raise floor to catalog ≥1.85 (CVE-2026-8763).
    // google-cloud-storage may pin older grpc-netty-shaded; raise floor to ≥1.75 (CVE-2025-55163).
    constraints {
        implementation(libs.bouncycastle.bcprov) {
            because("CVE-2026-8763: bcprov-jdk18on before 1.85 is blocked by Security CI CRITICAL policy")
        }
        implementation(libs.grpc.netty.shaded) {
            because("CVE-2025-55163: grpc-netty-shaded before 1.75.0 is blocked by Security CI HIGH library policy")
        }
    }

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    // @AutoConfigureMetrics: the Prometheus endpoint tests need metrics export in the test context.
    testImplementation(libs.spring.boot.micrometer.metrics.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.restclient.test)
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

// N-02 real-provider acceptance harness (#483). Test classpath only, so it is never packaged in the
// service image. `plan <dir>` runs the case sequence against an offline model without network;
// `run <dir>` drives the merged GCS adapter and store against the owner-approved test bucket, with
// settings from N02_GCS_* variables and Application Default Credentials impersonating one service
// account per identity. The checkout's SHA, tree and the state of the build inputs (tracked changes
// and untracked files) are read from git when the task runs and recorded in the result; `run`
// refuses a modified checkout. Library logging goes to stderr at INFO, Google libraries at WARN.
tasks.register<JavaExec>("gcsProviderAcceptance") {
    group = "verification"
    description = "N-02 GCS Object Retention Lock acceptance harness (#483): plan offline; run only when approved"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.parkio.auth.infrastructure.durable.GcsAcceptanceTool")
    javaLauncher.set(javaToolchains.launcherFor(recoveryDrillToolchain))
    val checkout = rootProject.layout.projectDirectory.asFile
    doFirst {
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args).directory(checkout).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() != 0) {
                throw GradleException("git ${args.joinToString(" ")} failed: $text")
            }
            return text
        }
        val status = git("status", "--porcelain", "--untracked-files=all", "--", "services/auth-service", "buildSrc",
            "platform", "gradle", "gradlew", "settings.gradle.kts", "build.gradle.kts", "gradle.properties")
        systemProperty("n02.source.sha", git("rev-parse", "HEAD"))
        systemProperty("n02.source.tree", git("rev-parse", "HEAD^{tree}"))
        systemProperty("n02.source.dirty", status.isNotEmpty().toString())
        systemProperty("n02.source.status", status.lines().take(20).joinToString("; "))
        systemProperty("logback.configurationFile", "n02-gcs-acceptance-logback.xml")
    }
}
