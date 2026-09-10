# PostGIS bootstrap (M-POSTGIS) — parking cluster / parkio_parking only.
#
# Mode B (live Azure managed allow-list proof) remains HOLD.
# This module never modifies Flyway migration files.
# Live extension creation is gated by enable_live_bootstrap.

terraform {
  required_version = ">= 1.5.0, < 2.0.0"
  required_providers {
    postgresql = {
      source  = "cyrilgdn/postgresql"
      version = "~> 1.25"
    }
  }
}

variable "enable_live_bootstrap" {
  type    = bool
  default = false
}

variable "enable_postgis" {
  type        = bool
  description = "Must be true only for parking / parkio_parking."
  default     = true
}

variable "target_database" {
  type    = string
  default = "parkio_parking"
  validation {
    condition     = var.target_database == "parkio_parking"
    error_message = "PostGIS may only target parkio_parking."
  }
}

variable "cluster" {
  type = string
  validation {
    condition     = var.cluster == "parking"
    error_message = "PostGIS bootstrap is allowed only on the parking cluster."
  }
}

check "parking_only" {
  assert {
    condition     = var.cluster == "parking" && var.target_database == "parkio_parking"
    error_message = "PostGIS must be parking-only."
  }
}

# CREATE EXTENSION IF NOT EXISTS postgis — idempotent when live.
resource "postgresql_extension" "postgis" {
  count    = var.enable_live_bootstrap && var.enable_postgis ? 1 : 0
  name     = "postgis"
  database = var.target_database
}

output "postgis_enabled" {
  value = var.enable_postgis
}

output "target_database" {
  value = var.target_database
}

output "cluster" {
  value = var.cluster
}

output "mode_b_dependency" {
  value       = "HOLD"
  description = "Azure managed PostGIS allow-list proof requires SPIKE-02/03 Mode B (not executed)."
}

output "bootstrap_privilege_note" {
  value       = "Requires server admin/bootstrap identity with CREATE EXTENSION on parkio_parking; not an application role password."
  description = "Documented privilege boundary for operators."
}

output "flyway_untouched" {
  value = true
}
