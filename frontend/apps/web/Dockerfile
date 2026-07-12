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

ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_APP_ENV=$VITE_APP_ENV \
    VITE_SMART_RETURN_ENABLED=$VITE_SMART_RETURN_ENABLED \
    VITE_MAPTILER_KEY=$VITE_MAPTILER_KEY \
    VITE_MAPTILER_STYLE=$VITE_MAPTILER_STYLE \
    VITE_FRONTEND_ERROR_REPORTING=$VITE_FRONTEND_ERROR_REPORTING \
    VITE_WAITLIST_INTAKE_MODE=$VITE_WAITLIST_INTAKE_MODE

RUN test -n "$VITE_API_BASE_URL" || (echo "VITE_API_BASE_URL build-arg is required" >&2 && exit 1)
RUN corepack pnpm --filter @parkio/web build

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

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -q -O /dev/null http://localhost:80/ || exit 1
