# syntax=docker/dockerfile:1

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew
RUN ./gradlew :services:notification-service:bootJar --no-daemon

# ---- runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl is the ONLY added runtime package: the Docker HEALTHCHECK (defined in the
# compose apps overlay) uses it to probe the Spring Boot Actuator readiness endpoint.
# Installed before dropping privileges; apt lists are removed to keep the image lean.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Non-root, no-login system user. Numeric uid lets the orchestrator assert runAsNonRoot.
RUN useradd --system --uid 10001 --user-group --no-create-home parkio
COPY --from=build /workspace/services/notification-service/build/libs/*.jar app.jar
USER parkio
EXPOSE 8086

# JAVA_TOOL_OPTIONS (heap %, ExitOnOutOfMemoryError, etc.) is injected at runtime by the
# compose apps overlay so it can be tuned per environment without rebuilding the image.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
