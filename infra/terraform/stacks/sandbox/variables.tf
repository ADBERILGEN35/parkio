variable "environment" {
  type        = string
  description = "Must be sandbox for this stack."
  default     = "sandbox"
  validation {
    condition     = var.environment == "sandbox"
    error_message = "sandbox stack environment must be sandbox."
  }
}

variable "location" {
  type        = string
  description = "Azure location. No fabricated production default — must be supplied."
  # Offline authoring placeholder location (not a committed production region claim).
  default = "westeurope"
}

variable "naming_prefix" {
  type        = string
  description = "Temporary naming prefix for disposable sandbox resources."
  default     = "pp01b-sbx"
  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{0,20}[a-z0-9])?$", var.naming_prefix))
    error_message = "naming_prefix must be short lowercase alphanumeric/hyphen."
  }
}

variable "resource_group_name" {
  type        = string
  description = "Resource group name (sanitized placeholder OK for offline)."
  default     = "rg-pp01b-sandbox-offline"
}

variable "postgres_version" {
  type    = string
  default = "16"
  validation {
    condition     = startswith(var.postgres_version, "16")
    error_message = "postgres_version must be 16.x."
  }
}

variable "core_sku_name" {
  type        = string
  description = "Exact core SKU — must be provided for real apply; placeholder for offline authoring."
  default     = "GP_Standard_D2s_v3"
}

variable "parking_sku_name" {
  type        = string
  description = "Exact parking SKU — must be provided for real apply; placeholder for offline authoring."
  default     = "GP_Standard_D2s_v3"
}

variable "core_storage_mb" {
  type    = number
  default = 32768
}

variable "parking_storage_mb" {
  type    = number
  default = 32768
}

variable "core_backup_retention_days" {
  type    = number
  default = 7
  validation {
    condition     = var.core_backup_retention_days >= 1 && var.core_backup_retention_days <= 35
    error_message = "backup retention must be 1–35 days."
  }
}

variable "parking_backup_retention_days" {
  type    = number
  default = 7
}

variable "core_ha_mode" {
  type    = string
  default = "Disabled"
  validation {
    condition     = contains(["Disabled", "ZoneRedundant", "SameZone"], var.core_ha_mode)
    error_message = "Invalid core_ha_mode."
  }
}

variable "parking_ha_mode" {
  type    = string
  default = "Disabled"
  validation {
    condition     = contains(["Disabled", "ZoneRedundant", "SameZone"], var.parking_ha_mode)
    error_message = "Invalid parking_ha_mode."
  }
}

variable "create_network" {
  type        = bool
  description = "Create disposable VNet/subnet/DNS. Default false for offline authoring."
  default     = false
}

variable "existing_vnet_id" {
  type        = string
  description = "Sanitized offline placeholder VNet ID (zero subscription GUID)."
  default     = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-sandbox-offline/providers/Microsoft.Network/virtualNetworks/vnet-pp01b-offline"
}

variable "existing_delegated_subnet_id" {
  type    = string
  default = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-sandbox-offline/providers/Microsoft.Network/virtualNetworks/vnet-pp01b-offline/subnets/snet-pg-delegated"
}

variable "existing_private_dns_zone_id" {
  type    = string
  default = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-sandbox-offline/providers/Microsoft.Network/privateDnsZones/privatelink.postgres.database.azure.com"
}

variable "sandbox_address_space" {
  type    = list(string)
  default = ["10.250.0.0/16"]
}

variable "sandbox_subnet_prefix" {
  type    = list(string)
  default = ["10.250.1.0/24"]
}

variable "administrator_login" {
  type    = string
  default = "parkio_pg_admin"
}

variable "administrator_password" {
  type      = string
  sensitive = true
  default   = null
  nullable  = true
}

variable "service_role_passwords" {
  type      = map(string)
  sensitive = true
  default   = {}
}

variable "bootstrap_host" {
  type      = string
  default   = null
  nullable  = true
  sensitive = true
}

variable "bootstrap_username" {
  type     = string
  default  = null
  nullable = true
}

variable "bootstrap_password" {
  type      = string
  sensitive = true
  default   = null
  nullable  = true
}

variable "enable_live_bootstrap" {
  type        = bool
  description = "DB/role/PostGIS live provisioning. Default false — offline only in IAC-01."
  default     = false
}

variable "enable_postgis" {
  type    = bool
  default = true
}

variable "create_azure_resources" {
  type        = bool
  description = "When true, would create Flexible Servers (still requires apply_authorized + cost approval)."
  default     = false
}

variable "apply_authorized" {
  type        = bool
  description = "Must remain false for PP-01B-IAC-01."
  default     = false
}

variable "cost_approval_reference" {
  type        = string
  description = "Spend approval ticket/ref required before any future sandbox apply."
  default     = null
  nullable    = true
}

variable "sandbox_cleanup_deadline" {
  type        = string
  description = "ISO-8601 deadline for disposable sandbox cleanup responsibility."
  default     = null
  nullable    = true
}

variable "deletion_protection" {
  type        = bool
  description = "Sandbox defaults to false (disposable)."
  default     = false
}

variable "enable_diagnostics_placeholder" {
  type        = bool
  description = "Placeholder for future diagnostic settings (not wired in IAC-01)."
  default     = false
}

variable "tags" {
  type = map(string)
  default = {
    parkio_package = "pp-01b-iac-01"
    environment    = "sandbox"
  }
}

variable "public_network_access_enabled" {
  type    = bool
  default = false
  validation {
    condition     = var.public_network_access_enabled == false
    error_message = "Public DB access must remain disabled."
  }
}
