plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Make the Spring plugins available to the precompiled convention plugin so it
    // can apply them by id. Versions are single-sourced from the version catalog.
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${libs.versions.springBoot.get()}")
    implementation("io.spring.gradle:dependency-management-plugin:${libs.versions.springDependencyManagement.get()}")
}
