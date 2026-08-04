variable "environment" {
  type    = string
  default = "production"
  validation {
    condition     = var.environment == "production"
    error_message = "production stack environment must be production."
  }
}

variable "location" {
  type        = string
  description = "Must be supplied for a future production program; placeholder only for offline validate."
  default     = "westeurope"
}

variable "naming_prefix" {
  type        = string
  description = "Non-production naming placeholder — not a real production hostname prefix."
  default     = "pp01b-prd-def"
}

variable "resource_group_name" {
  type    = string
  default = "rg-pp01b-production-definition-only"
}

variable "postgres_version" {
  type    = string
  default = "16"
}

variable "core_sku_name" {
  type        = string
  description = "Exact production SKU is an open condition; GP family placeholder for validation only."
  default     = "GP_Standard_D4s_v3"
}

variable "parking_sku_name" {
  type    = string
  default = "GP_Standard_D4s_v3"
}

variable "core_storage_mb" {
  type    = number
  default = 131072
}

variable "parking_storage_mb" {
  type    = number
  default = 131072
}

variable "core_backup_retention_days" {
  type    = number
  default = 30
  validation {
    condition     = var.core_backup_retention_days >= 30
    error_message = "Production PITR retention must be >= 30 days."
  }
}

variable "parking_backup_retention_days" {
  type    = number
  default = 30
  validation {
    condition     = var.parking_backup_retention_days >= 30
    error_message = "Production PITR retention must be >= 30 days."
  }
}

variable "core_ha_mode" {
  type    = string
  default = "ZoneRedundant"
  validation {
    condition     = var.core_ha_mode == "ZoneRedundant"
    error_message = "Production HA must be ZoneRedundant."
  }
}

variable "parking_ha_mode" {
  type    = string
  default = "ZoneRedundant"
  validation {
    condition     = var.parking_ha_mode == "ZoneRedundant"
    error_message = "Production HA must be ZoneRedundant."
  }
}

variable "existing_vnet_id" {
  type        = string
  description = "Sanitized zero-GUID placeholder — not a real production VNet."
  default     = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-production-definition-only/providers/Microsoft.Network/virtualNetworks/vnet-pp01b-definition"
}

variable "existing_delegated_subnet_id" {
  type    = string
  default = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-production-definition-only/providers/Microsoft.Network/virtualNetworks/vnet-pp01b-definition/subnets/snet-pg-delegated"
}

variable "existing_private_dns_zone_id" {
  type    = string
  default = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-production-definition-only/providers/Microsoft.Network/privateDnsZones/privatelink.postgres.database.azure.com"
}

variable "administrator_login" {
  type    = string
  default = "parkio_pg_admin"
}

variable "public_network_access_enabled" {
  type    = bool
  default = false
  validation {
    condition     = var.public_network_access_enabled == false
    error_message = "Production public access must be false."
  }
}

variable "production_enablement" {
  type        = bool
  description = "Must default false and remain rejected during PP-01B."
  default     = false
  validation {
    condition     = var.production_enablement == false
    error_message = "production_enablement must be false."
  }
}

variable "create_azure_resources" {
  type    = bool
  default = false
  validation {
    condition     = var.create_azure_resources == false
    error_message = "create_azure_resources must be false for production definition stack."
  }
}

variable "apply_authorized" {
  type    = bool
  default = false
  validation {
    condition     = var.apply_authorized == false
    error_message = "apply_authorized must be false — production apply impossible in this package."
  }
}

variable "tags" {
  type = map(string)
  default = {
    parkio_package = "pp-01b-iac-01"
    environment    = "production"
    apply_status   = "definition-only"
  }
}
