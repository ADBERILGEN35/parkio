# syntax=docker/dockerfile:1

# ---- read-only Gradle dependency cache (#638) ----
# An empty stage unless the build passes `--build-context gradle-ro-dep-cache=<dir>`. CI passes a copy
# of Backend CI's dependency cache there (scripts/ci/gradle_ro_dep_cache.py). Gradle then takes each
# dependency it finds there instead of downloading it, never writes to it, and downloads the others
# as before. The bind mount below is in no layer of either stage.
FROM scratch AS gradle-ro-dep-cache

# ---- build stage ----
FROM eclipse-temurin@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS build
WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew
RUN --mount=type=bind,from=gradle-ro-dep-cache,target=/gradle-ro-dep-cache \
    if [ -d /gradle-ro-dep-cache/modules-2 ]; then export GRADLE_RO_DEP_CACHE=/gradle-ro-dep-cache; fi \
 && ./gradlew :services:user-service:bootJar --no-daemon

# ---- runtime stage ----
FROM eclipse-temurin@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS runtime
WORKDIR /app

# curl is the ONLY added runtime package: the HEALTHCHECK below (and the compose apps
# overlay) use it to probe the Spring Boot Actuator readiness endpoint. apt lists removed.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# The Ubuntu 26.04 base layer (built with Rockcraft) ships /usr/bin/pebble, a Go service manager
# that nothing here runs: the base image clears its entrypoint, the temurin entrypoint never calls
# it, and this image starts java directly. Removing it keeps that binary's Go stdlib findings
# (Security CI library gate) out of the image filesystem.
RUN rm -f /usr/bin/pebble

# OCI image metadata (supply chain / provenance). Mutable fields are passed by CI at build
# time (--build-arg); none are invented. `source` is the canonical repository. No `licenses`
# label until a LICENSE file is added to the repo (do not invent a license).
ARG IMAGE_VERSION=0.0.1-SNAPSHOT
ARG IMAGE_REVISION=unknown
ARG IMAGE_CREATED=""
LABEL org.opencontainers.image.title="parkio-user-service" \
      org.opencontainers.image.description="User profiles and account management" \
      org.opencontainers.image.source="https://github.com/ADBERILGEN35/parkio" \
      org.opencontainers.image.url="https://github.com/ADBERILGEN35/parkio" \
      org.opencontainers.image.documentation="https://github.com/ADBERILGEN35/parkio/blob/master/README.md" \
      org.opencontainers.image.vendor="Parkio" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${IMAGE_REVISION}" \
      org.opencontainers.image.created="${IMAGE_CREATED}"

# Non-root, no-login system user. Numeric uid lets the orchestrator assert runAsNonRoot.
RUN useradd --system --uid 10001 --user-group --no-create-home parkio
COPY --from=build /workspace/services/user-service/build/libs/*.jar app.jar
USER parkio
EXPOSE 8082

# Self-contained readiness probe (also defined in the compose apps overlay for tunability).
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8082/actuator/health/readiness || exit 1

# JAVA_TOOL_OPTIONS (heap %, ExitOnOutOfMemoryError, etc.) is injected at runtime by the
# compose apps overlay so it can be tuned per environment without rebuilding the image.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
