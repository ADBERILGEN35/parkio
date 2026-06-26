# syntax=docker/dockerfile:1

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew
RUN ./gradlew :services:parking-service:bootJar --no-daemon

# ---- runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl is the ONLY added runtime package: the HEALTHCHECK below (and the compose apps
# overlay) use it to probe the Spring Boot Actuator readiness endpoint. apt lists removed.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# OCI image metadata (supply chain / provenance). Mutable fields are passed by CI at build
# time (--build-arg); none are invented. `source` is the canonical repository. No `licenses`
# label until a LICENSE file is added to the repo (do not invent a license).
ARG IMAGE_VERSION=0.0.1-SNAPSHOT
ARG IMAGE_REVISION=unknown
ARG IMAGE_CREATED=""
LABEL org.opencontainers.image.title="parkio-parking-service" \
      org.opencontainers.image.description="Parking spots, availability and reservations" \
      org.opencontainers.image.source="https://github.com/ADBERILGEN35/parkio" \
      org.opencontainers.image.url="https://github.com/ADBERILGEN35/parkio" \
      org.opencontainers.image.documentation="https://github.com/ADBERILGEN35/parkio/blob/master/README.md" \
      org.opencontainers.image.vendor="Parkio" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${IMAGE_REVISION}" \
      org.opencontainers.image.created="${IMAGE_CREATED}"

# Non-root, no-login system user. Numeric uid lets the orchestrator assert runAsNonRoot.
RUN useradd --system --uid 10001 --user-group --no-create-home parkio
COPY --from=build /workspace/services/parking-service/build/libs/*.jar app.jar
USER parkio
EXPOSE 8083

# Self-contained readiness probe (also defined in the compose apps overlay for tunability).
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8083/actuator/health/readiness || exit 1

# JAVA_TOOL_OPTIONS (heap %, ExitOnOutOfMemoryError, etc.) is injected at runtime by the
# compose apps overlay so it can be tuned per environment without rebuilding the image.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
