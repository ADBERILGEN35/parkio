#!/usr/bin/env bash
# Authoritative Postgres compose service list for isolated restore drills.
# Must match scripts/backup-databases.sh + scripts/restore-drill.sh (10 DBs).
# shellcheck shell=bash

PARKIO_POSTGRES_COMPOSE_SERVICES=(
  postgres-auth
  postgres-gateway
  postgres-user
  postgres-parking
  postgres-media
  postgres-gamification
  postgres-notification
  postgres-moderation
  postgres-analytics
  postgres-ai-validation
)
