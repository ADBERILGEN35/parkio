rootProject.name = "parkio"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Each backend service is an independently runnable Gradle module.
// No shared domain modules are declared on purpose: services do not share models.
include(
    "services:gateway-service",
    "services:auth-service",
    "services:user-service",
    "services:parking-service",
    "services:media-service",
    "services:gamification-service",
    "services:notification-service",
    "services:moderation-service",
    "services:ai-validation-service",
    "services:analytics-service",
)
