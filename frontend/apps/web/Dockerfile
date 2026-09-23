# syntax=docker/dockerfile:1

# ---- build stage ----
FROM node:22-alpine3.24@sha256:b6f26b36c8ff49624cfdac716b8ea1138d606df02586a77d364bb5536a634f85 AS build
WORKDIR /workspace

# Patch the bundled npm used in the build environment.
RUN npm install -g npm@11.20.0 --ignore-scripts

# pnpm via corepack (matches repo toolchain).
RUN corepack enable

COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
COPY frontend/packages ./packages
COPY frontend/apps/web ./apps/web

# The prior image did not copy frontend/.npmrc, so it used pnpm's isolated
# linker. Preserve that builder layout while keeping the workspace's hoisted
# linker for developer and CI installs. Install only the web dependency graph.
RUN corepack pnpm@11.18.0 --config.node-linker=isolated \
    --filter @parkio/web... install --frozen-lockfile

# Build-time public config (baked into the static bundle).
ARG VITE_API_BASE_URL
ARG VITE_APP_ENV=hosted-beta
ARG VITE_SMART_RETURN_ENABLED=true
ARG VITE_MAPTILER_KEY=
ARG VITE_MAPTILER_STYLE=streets-v2
ARG VITE_FRONTEND_ERROR_REPORTING=disabled
ARG VITE_WAITLIST_INTAKE_MODE=api
ARG VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false
ARG VITE_SMART_PARKING_ASSISTANT_ENABLED=false
ARG VITE_PUBLIC_EXPLORE_ENABLED=false
ARG VITE_REGISTRATION_MODE=closed
# Optional post-build verify-bundle-env contracts (release / municipal-on candidates).
ARG VERIFY_REQUIRE_PUBLIC_EXPLORE=
ARG VERIFY_REQUIRE_MUNICIPAL=

ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_APP_ENV=$VITE_APP_ENV \
    VITE_SMART_RETURN_ENABLED=$VITE_SMART_RETURN_ENABLED \
    VITE_MAPTILER_KEY=$VITE_MAPTILER_KEY \
    VITE_MAPTILER_STYLE=$VITE_MAPTILER_STYLE \
    VITE_FRONTEND_ERROR_REPORTING=$VITE_FRONTEND_ERROR_REPORTING \
    VITE_WAITLIST_INTAKE_MODE=$VITE_WAITLIST_INTAKE_MODE \
    VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=$VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED \
    VITE_SMART_PARKING_ASSISTANT_ENABLED=$VITE_SMART_PARKING_ASSISTANT_ENABLED \
    VITE_PUBLIC_EXPLORE_ENABLED=$VITE_PUBLIC_EXPLORE_ENABLED \
    VITE_REGISTRATION_MODE=$VITE_REGISTRATION_MODE \
    VERIFY_REQUIRE_PUBLIC_EXPLORE=$VERIFY_REQUIRE_PUBLIC_EXPLORE \
    VERIFY_REQUIRE_MUNICIPAL=$VERIFY_REQUIRE_MUNICIPAL

# Pre-build gate. A production-shaped build must never bake an empty required public value:
# src/config/env.ts validates during module evaluation, so the SPA throws before React
# mounts and every route renders blank while nginx still answers 200 (defect I11, rc5).
# Fail here instead. The validator reads values from process.env internally so the Docker
# RUN instruction never expands them into BuildKit's progress log.
RUN node apps/web/scripts/validate-build-env.mjs

RUN corepack pnpm@11.18.0 --filter @parkio/web build

# Post-build gate: assert the emitted artifact itself carries non-empty required values and
# was built for the intended app env. This catches build-arg mis-wiring that the pre-build
# check cannot see, and it inspects the bundle rather than trusting the build inputs.
# Optional VERIFY_REQUIRE_* env contracts are set by release / municipal-on bake paths.
RUN set -e; \
    args="--dist apps/web/dist --app-env ${VITE_APP_ENV}"; \
    if [ "${VERIFY_REQUIRE_PUBLIC_EXPLORE}" = "true" ]; then \
      args="${args} --require-public-explore true"; \
    fi; \
    if [ "${VERIFY_REQUIRE_MUNICIPAL}" = "true" ]; then \
      args="${args} --require-municipal true"; \
    fi; \
    # shellcheck disable=SC2086 \
    node apps/web/scripts/verify-bundle-env.mjs ${args}

# ---- runtime stage ----
FROM nginx:1.30.5-alpine3.24 AS runtime

ARG IMAGE_VERSION=0.0.1-SNAPSHOT
ARG IMAGE_REVISION=unknown
ARG IMAGE_CREATED=""
LABEL org.opencontainers.image.title="parkio-web" \
      org.opencontainers.image.description="Parkio hosted-beta static SPA (Vite build served by nginx)" \
      org.opencontainers.image.source="https://github.com/ADBERILGEN35/parkio" \
      org.opencontainers.image.url="https://github.com/ADBERILGEN35/parkio" \
      org.opencontainers.image.documentation="https://github.com/ADBERILGEN35/parkio/blob/master/README.md" \
      org.opencontainers.image.vendor="Parkio" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${IMAGE_REVISION}" \
      org.opencontainers.image.created="${IMAGE_CREATED}"

COPY frontend/apps/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/web/dist /usr/share/nginx/html
RUN find /usr/share/nginx/html -type d -exec chmod 0755 {} \; \
 && find /usr/share/nginx/html -type f -exec chmod 0644 {} \;

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:80/ || exit 1
