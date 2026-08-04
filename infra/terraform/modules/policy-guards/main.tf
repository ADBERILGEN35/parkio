# Policy / topology guards (M-POLICY-GUARDS)
# Pure locals + check blocks — no cloud calls.

terraform {
  required_version = ">= 1.5.0, < 2.0.0"
}

variable "environment" {
  type = string
  validation {
    condition     = contains(["sandbox", "staging", "production"], var.environment)
    error_message = "environment must be sandbox, staging, or production."
  }
}

variable "server_count" {
  type = number
}

variable "database_count" {
  type = number
}

variable "role_count" {
  type = number
}

variable "service_manifest" {
  type = map(object({
    database = string
    role     = string
    cluster  = string
  }))
}

variable "postgis_on_core" {
  type = bool
}

variable "postgis_on_parking" {
  type = bool
}

variable "public_network_access_enabled" {
  type = bool
}

variable "core_backup_retention_days" {
  type = number
}

variable "parking_backup_retention_days" {
  type = number
}

variable "core_ha_mode" {
  type = string
}

variable "parking_ha_mode" {
  type = string
}

variable "core_sku_name" {
  type = string
}

variable "parking_sku_name" {
  type = string
}

variable "production_shaped" {
  type = bool
}

variable "apply_authorized" {
  type        = bool
  description = "Must remain false for production during PP-01B-IAC-01."
  default     = false
}

variable "tls_client_mode" {
  type = string
}

variable "provider_topology" {
  type        = string
  description = "Frozen primary topology identifier."
}

locals {
  expected_manifest = {
    "auth-service"          = { database = "parkio_auth", role = "parkio_auth", cluster = "core" }
    "gateway-service"       = { database = "parkio_gateway", role = "parkio_gateway", cluster = "core" }
    "user-service"          = { database = "parkio_user", role = "parkio_user", cluster = "core" }
    "media-service"         = { database = "parkio_media", role = "parkio_media", cluster = "core" }
    "gamification-service"  = { database = "parkio_gamification", role = "parkio_gamification", cluster = "core" }
    "notification-service"  = { database = "parkio_notification", role = "parkio_notification", cluster = "core" }
    "moderation-service"    = { database = "parkio_moderation", role = "parkio_moderation", cluster = "core" }
    "analytics-service"     = { database = "parkio_analytics", role = "parkio_analytics", cluster = "core" }
    "ai-validation-service" = { database = "parkio_aivalidation", role = "parkio_aivalidation", cluster = "core" }
    "parking-service"       = { database = "parkio_parking", role = "parkio_parking", cluster = "parking" }
  }

  databases = [for _, v in var.service_manifest : v.database]
  roles     = [for _, v in var.service_manifest : v.role]

  parking_ok = try(var.service_manifest["parking-service"].cluster, "") == "parking"
  non_parking_on_parking = length([
    for svc, cfg in var.service_manifest :
    svc if svc != "parking-service" && cfg.cluster == "parking"
  ]) == 0
  parking_on_core = try(var.service_manifest["parking-service"].cluster, "") == "core"

  core_burstable    = can(regex("(?i)^B_", var.core_sku_name))
  parking_burstable = can(regex("(?i)^B_", var.parking_sku_name))
  core_ha           = var.core_ha_mode != "Disabled"
  parking_ha        = var.parking_ha_mode != "Disabled"
}

check "server_count_is_two" {
  assert {
    condition     = var.server_count == 2
    error_message = "server_count must be exactly 2 (core + parking)."
  }
}

check "db_count_is_ten" {
  assert {
    condition     = var.database_count == 10 && length(toset(local.databases)) == 10
    error_message = "Exactly 10 unique databases required."
  }
}

check "role_count_is_ten" {
  assert {
    condition     = var.role_count == 10 && length(toset(local.roles)) == 10
    error_message = "Exactly 10 unique roles required."
  }
}

check "manifest_matches_adr" {
  assert {
    condition = (
      length(var.service_manifest) == length(local.expected_manifest) &&
      length(setsubtract(keys(var.service_manifest), keys(local.expected_manifest))) == 0 &&
      length(setsubtract(keys(local.expected_manifest), keys(var.service_manifest))) == 0 &&
      alltrue([
        for k, v in local.expected_manifest :
        try(var.service_manifest[k].database, null) == v.database &&
        try(var.service_manifest[k].role, null) == v.role &&
        try(var.service_manifest[k].cluster, null) == v.cluster
      ])
    )
    error_message = "service_manifest must exactly match the frozen ADR inventory."
  }
}

check "parking_maps_to_parking" {
  assert {
    condition     = local.parking_ok && !local.parking_on_core
    error_message = "parking-service must map to parking cluster, not core."
  }
}

check "no_non_parking_on_parking" {
  assert {
    condition     = local.non_parking_on_parking
    error_message = "Non-parking services must not map to parking."
  }
}

check "postgis_not_on_core" {
  assert {
    condition     = var.postgis_on_core == false
    error_message = "PostGIS must not be enabled on core."
  }
}

check "postgis_on_parking" {
  assert {
    condition     = var.postgis_on_parking == true
    error_message = "PostGIS must be enabled for parking/parkio_parking."
  }
}

check "public_access_disabled" {
  assert {
    condition     = var.public_network_access_enabled == false
    error_message = "Public network access must be disabled."
  }
}

check "production_shaped_pitr" {
  assert {
    condition = (
      !var.production_shaped ||
      (var.core_backup_retention_days >= 30 && var.parking_backup_retention_days >= 30)
    )
    error_message = "production-shaped PITR retention must be >= 30 days on both servers."
  }
}

check "production_shaped_ha" {
  assert {
    condition = (
      !var.production_shaped ||
      (var.core_ha_mode == "ZoneRedundant" && var.parking_ha_mode == "ZoneRedundant")
    )
    error_message = "production-shaped HA must be ZoneRedundant on both servers."
  }
}

check "no_burstable_with_ha" {
  assert {
    condition = (
      !(local.core_burstable && local.core_ha) &&
      !(local.parking_burstable && local.parking_ha)
    )
    error_message = "Burstable SKU with HA is forbidden."
  }
}

check "production_apply_forbidden" {
  assert {
    condition = (
      var.environment != "production" || var.apply_authorized == false
    )
    error_message = "Production apply_authorized must be false during PP-01B-IAC-01."
  }
}

check "tls_verify_full" {
  assert {
    condition     = var.tls_client_mode == "verify-full"
    error_message = "Client TLS handoff target must be sslmode=verify-full."
  }
}

check "frozen_provider_topology" {
  assert {
    condition     = var.provider_topology == "azure-postgresql-flexible-server-vnet-private-dns"
    error_message = "Unsupported provider/topology swap."
  }
}

output "policy_passed" {
  value = true
}

output "guards" {
  value = {
    server_count                = var.server_count
    database_count              = var.database_count
    role_count                  = var.role_count
    public_network_access       = var.public_network_access_enabled
    postgis_on_core             = var.postgis_on_core
    postgis_on_parking          = var.postgis_on_parking
    production_apply_authorized = var.apply_authorized
    tls_client_mode             = var.tls_client_mode
    provider_topology           = var.provider_topology
  }
}
