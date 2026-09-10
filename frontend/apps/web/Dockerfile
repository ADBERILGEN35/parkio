# syntax=docker/dockerfile:1

# ---- build stage ----
FROM node:22-bookworm-slim AS build
WORKDIR /workspace

# pnpm via corepack (matches repo toolchain).
RUN corepack enable

COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
COPY frontend/packages ./packages
COPY frontend/apps/web ./apps/web

RUN corepack pnpm install --frozen-lockfile

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
    VITE_REGISTRATION_MODE=$VITE_REGISTRATION_MODE

# Pre-build gate. A production-shaped build must never bake an empty required public value:
# src/config/env.ts validates during module evaluation, so the SPA throws before React
# mounts and every route renders blank while nginx still answers 200 (defect I11, rc5).
# Fail here instead. The validator reads values from process.env internally so the Docker
# RUN instruction never expands them into BuildKit's progress log.
RUN node apps/web/scripts/validate-build-env.mjs

RUN corepack pnpm --filter @parkio/web build

# Post-build gate: assert the emitted artifact itself carries non-empty required values and
# was built for the intended app env. This catches build-arg mis-wiring that the pre-build
# check cannot see, and it inspects the bundle rather than trusting the build inputs.
RUN node apps/web/scripts/verify-bundle-env.mjs \
      --dist apps/web/dist \
      --app-env "$VITE_APP_ENV"

# ---- runtime stage ----
FROM nginx:1.27-alpine AS runtime

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
