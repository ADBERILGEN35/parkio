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
  description = "Azure location. Mode B approved: francecentral."
  default     = "francecentral"
}

variable "naming_prefix" {
  type        = string
  description = "Temporary naming prefix for disposable sandbox resources."
  default     = "pp01b-mb-20260804-7nr2"
  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{0,20}[a-z0-9])?$", var.naming_prefix))
    error_message = "naming_prefix must be short lowercase alphanumeric/hyphen (max 22)."
  }
}

variable "resource_group_name" {
  type        = string
  description = "Disposable Mode B RG name (created when create_disposable_rg=true)."
  default     = "rg-pp01b-mb-20260804-7nr2"
  validation {
    condition     = !can(regex("hosted-beta", var.resource_group_name))
    error_message = "Must not reference hosted-beta."
  }
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
  type    = string
  default = "GP_Standard_D2s_v3"
}

variable "parking_sku_name" {
  type    = string
  default = "GP_Standard_D2s_v3"
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
  default = 30
  validation {
    condition     = var.core_backup_retention_days >= 1 && var.core_backup_retention_days <= 35
    error_message = "backup retention must be 1–35 days."
  }
}

variable "parking_backup_retention_days" {
  type    = number
  default = 30
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

variable "create_disposable_rg" {
  type        = bool
  description = "Create Terraform-owned disposable Mode B RG. Requires apply_authorized."
  default     = false
}

variable "create_network" {
  type        = bool
  description = "Create disposable VNet/subnets/DNS. Requires apply_authorized."
  default     = false
}

variable "create_probe" {
  type        = bool
  description = "Create private probe VM. Requires apply_authorized + create_network (or existing probe subnet)."
  default     = false
}

variable "create_azure_resources" {
  type        = bool
  description = "Create Flexible Servers. Requires apply_authorized + network ready + provider registered."
  default     = false
}

variable "apply_authorized" {
  type        = bool
  description = "Temporary Mode B unlock. Default false. Requires exact auth reference + cost + deadline."
  default     = false
}

variable "authorization_reference" {
  type        = string
  description = "Must be PP-01B-MODE-B-20260804-01 to unlock Mode B apply."
  default     = null
  nullable    = true
}

variable "cost_approval_reference" {
  type        = string
  description = "Spend approval ticket/ref required before sandbox apply."
  default     = null
  nullable    = true
}

variable "sandbox_cleanup_deadline" {
  type        = string
  description = "ISO-8601 deadline for disposable sandbox cleanup."
  default     = null
  nullable    = true
}

variable "provider_postgresql_registered" {
  type        = bool
  description = "Proof input that Microsoft.DBforPostgreSQL is Registered (Step 2). Required when creating servers."
  default     = false
}

variable "existing_vnet_id" {
  type        = string
  description = "Offline placeholder when create_network=false."
  default     = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-sandbox-offline/providers/Microsoft.Network/virtualNetworks/vnet-pp01b-offline"
}

variable "existing_delegated_subnet_id" {
  type    = string
  default = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-sandbox-offline/providers/Microsoft.Network/virtualNetworks/vnet-pp01b-offline/subnets/snet-pg-delegated"
}

variable "existing_probe_subnet_id" {
  type    = string
  default = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-sandbox-offline/providers/Microsoft.Network/virtualNetworks/vnet-pp01b-offline/subnets/snet-probe"
}

variable "existing_private_dns_zone_id" {
  type    = string
  default = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-sandbox-offline/providers/Microsoft.Network/privateDnsZones/pp01b-mb-20260804-7nr2.private.postgres.database.azure.com"
}

variable "sandbox_address_space" {
  type    = list(string)
  default = ["10.251.0.0/16"]
}

variable "postgres_subnet_prefixes" {
  type    = list(string)
  default = ["10.251.1.0/24"]
}

variable "probe_subnet_prefixes" {
  type    = list(string)
  default = ["10.251.2.0/24"]
}

variable "private_dns_zone_name" {
  type    = string
  default = "pp01b-mb-20260804-7nr2.private.postgres.database.azure.com"
  validation {
    condition = (
      endswith(var.private_dns_zone_name, ".postgres.database.azure.com") &&
      var.private_dns_zone_name != "privatelink.postgres.database.azure.com" &&
      !startswith(var.private_dns_zone_name, "privatelink.")
    )
    error_message = "Must use VNet-integration DNS suffix .postgres.database.azure.com (not privatelink)."
  }
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
  description = "DB/role/PostGIS live provisioning. Requires create_azure_resources and both endpoints."
  default     = false
}

variable "enable_postgis" {
  type    = bool
  default = true
}

variable "probe_vm_size" {
  type    = string
  default = "Standard_B1s"
}

variable "probe_ssh_public_key" {
  type      = string
  sensitive = true
  default   = null
  nullable  = true
}

variable "deletion_protection" {
  type    = bool
  default = false
}

variable "enable_diagnostics_placeholder" {
  type    = bool
  default = false
}

variable "tags" {
  type = map(string)
  default = {
    parkio_package = "pp-01b-mode-b"
    environment    = "sandbox"
    program        = "pp-01b"
    temporary      = "true"
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
