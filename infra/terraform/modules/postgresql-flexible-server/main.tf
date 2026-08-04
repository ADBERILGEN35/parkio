# Reusable Azure Database for PostgreSQL Flexible Server module.
# Instantiated twice: core and parking. Public network access always disabled.

terraform {
  required_version = ">= 1.5.0, < 2.0.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.26"
    }
  }
}

variable "name" {
  type        = string
  description = "Flexible Server name (unique within Azure)."
}

variable "resource_group_name" {
  type = string
}

variable "location" {
  type = string
  validation {
    condition     = length(trimspace(var.location)) > 0
    error_message = "location must be non-empty (no fabricated default)."
  }
}

variable "postgres_version" {
  type    = string
  default = "16"
  validation {
    condition     = startswith(var.postgres_version, "16")
    error_message = "postgres_version must be PostgreSQL 16 family."
  }
}

variable "sku_name" {
  type        = string
  description = "Exact SKU (e.g. GP_Standard_D2s_v3). No fabricated default."
  validation {
    condition     = length(trimspace(var.sku_name)) > 0
    error_message = "sku_name is required."
  }
}

variable "storage_mb" {
  type = number
  validation {
    condition     = var.storage_mb > 0
    error_message = "storage_mb must be > 0."
  }
}

variable "backup_retention_days" {
  type = number
}

variable "ha_mode" {
  type        = string
  description = "Disabled | ZoneRedundant | SameZone"
  validation {
    condition     = contains(["Disabled", "ZoneRedundant", "SameZone"], var.ha_mode)
    error_message = "ha_mode must be Disabled, ZoneRedundant, or SameZone."
  }
}

variable "environment" {
  type = string
  validation {
    condition     = contains(["sandbox", "staging", "production"], var.environment)
    error_message = "environment must be sandbox, staging, or production."
  }
}

variable "production_shaped" {
  type    = bool
  default = false
}

variable "public_network_access_enabled" {
  type    = bool
  default = false
  validation {
    condition     = var.public_network_access_enabled == false
    error_message = "public_network_access_enabled must be false (private posture)."
  }
}

variable "delegated_subnet_id" {
  type = string
  validation {
    condition     = length(trimspace(var.delegated_subnet_id)) > 0
    error_message = "delegated_subnet_id is required for private access."
  }
}

variable "private_dns_zone_id" {
  type = string
  validation {
    condition     = length(trimspace(var.private_dns_zone_id)) > 0
    error_message = "private_dns_zone_id is required for private access."
  }
}

variable "administrator_login" {
  type = string
  validation {
    condition = (
      length(var.administrator_login) > 0 &&
      !contains([
        "parkio_auth", "parkio_gateway", "parkio_user", "parkio_media",
        "parkio_gamification", "parkio_notification", "parkio_moderation",
        "parkio_analytics", "parkio_aivalidation", "parkio_parking"
      ], var.administrator_login)
    )
    error_message = "administrator_login must not be an application role name."
  }
}

variable "administrator_password" {
  type        = string
  sensitive   = true
  description = "Injected at apply time only; never commit. Null allowed when create_resource=false."
  default     = null
  nullable    = true
}

variable "zone" {
  type     = string
  default  = null
  nullable = true
}

variable "deletion_protection" {
  type    = bool
  default = false
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "cluster_role" {
  type        = string
  description = "core or parking — informational tag/contract."
  validation {
    condition     = contains(["core", "parking"], var.cluster_role)
    error_message = "cluster_role must be core or parking."
  }
}

variable "create_resource" {
  type        = bool
  description = "When false, module validates inputs/checks only (offline authoring). No Azure call."
  default     = false
}

locals {
  sku_is_burstable = can(regex("(?i)^B_", var.sku_name)) || can(regex("(?i)Burstable", var.sku_name))
  ha_enabled       = var.ha_mode != "Disabled"
  require_pitr_30  = var.environment == "production" || var.production_shaped
}

check "reject_burstable_with_ha" {
  assert {
    condition     = !(local.sku_is_burstable && local.ha_enabled)
    error_message = "Burstable SKU is forbidden when HA is enabled (SPIKE-01 / PP-01B-R0)."
  }
}

check "reject_low_pitr_when_production_shaped" {
  assert {
    condition     = !local.require_pitr_30 || var.backup_retention_days >= 30
    error_message = "backup_retention_days must be >= 30 for production or production_shaped."
  }
}

check "production_ha_zone_redundant" {
  assert {
    condition = (
      !(var.environment == "production" && var.production_shaped) ||
      var.ha_mode == "ZoneRedundant"
    )
    error_message = "production-shaped topology requires ZoneRedundant HA."
  }
}

check "password_required_when_creating" {
  assert {
    condition = (
      !var.create_resource ||
      (var.administrator_password != null && length(var.administrator_password) >= 16)
    )
    error_message = "administrator_password (>=16) required when create_resource=true."
  }
}

resource "azurerm_postgresql_flexible_server" "this" {
  count = var.create_resource ? 1 : 0

  name                          = var.name
  resource_group_name           = var.resource_group_name
  location                      = var.location
  version                       = var.postgres_version
  sku_name                      = var.sku_name
  storage_mb                    = var.storage_mb
  backup_retention_days         = var.backup_retention_days
  administrator_login           = var.administrator_login
  administrator_password        = var.administrator_password
  delegated_subnet_id           = var.delegated_subnet_id
  private_dns_zone_id           = var.private_dns_zone_id
  public_network_access_enabled = false
  zone                          = var.zone
  tags = merge(var.tags, {
    parkio_cluster = var.cluster_role
  })

  dynamic "high_availability" {
    for_each = local.ha_enabled ? [1] : []
    content {
      mode = var.ha_mode
    }
  }
}

output "id" {
  value       = try(azurerm_postgresql_flexible_server.this[0].id, null)
  description = "Flexible Server resource ID (null in offline authoring)."
}

output "fqdn" {
  value       = try(azurerm_postgresql_flexible_server.this[0].fqdn, null)
  description = "Private DNS FQDN — PP-01C handoff (non-secret)."
}

output "name" {
  value = var.name
}

output "postgres_version" {
  value = var.postgres_version
}

output "ha_mode" {
  value = var.ha_mode
}

output "backup_retention_days" {
  value = var.backup_retention_days
}

output "public_network_access_enabled" {
  value = var.public_network_access_enabled
}

output "cluster_role" {
  value = var.cluster_role
}

output "create_resource" {
  value = var.create_resource
}

output "deletion_protection" {
  value = var.deletion_protection
}

output "pp01c_tls_mode" {
  value       = "verify-full"
  description = "PP-01C client TLS target; not applied by Terraform."
}
