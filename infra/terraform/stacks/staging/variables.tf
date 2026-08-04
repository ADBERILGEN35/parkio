variable "environment" {
  type    = string
  default = "staging"
  validation {
    condition     = var.environment == "staging"
    error_message = "staging stack environment must be staging."
  }
}

variable "location" {
  type    = string
  default = "westeurope"
}

variable "naming_prefix" {
  type    = string
  default = "pp01b-stg"
}

variable "resource_group_name" {
  type    = string
  default = "rg-pp01b-staging-offline"
}

variable "postgres_version" {
  type    = string
  default = "16"
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
  default = 65536
}

variable "parking_storage_mb" {
  type    = number
  default = 65536
}

variable "core_backup_retention_days" {
  type    = number
  default = 14
}

variable "parking_backup_retention_days" {
  type    = number
  default = 14
}

variable "core_ha_mode" {
  type    = string
  default = "Disabled"
}

variable "parking_ha_mode" {
  type    = string
  default = "Disabled"
}

variable "production_shaped" {
  type    = bool
  default = false
}

variable "existing_vnet_id" {
  type    = string
  default = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-staging-offline/providers/Microsoft.Network/virtualNetworks/vnet-pp01b-offline"
}

variable "existing_delegated_subnet_id" {
  type    = string
  default = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-staging-offline/providers/Microsoft.Network/virtualNetworks/vnet-pp01b-offline/subnets/snet-pg-delegated"
}

variable "existing_private_dns_zone_id" {
  type    = string
  default = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pp01b-staging-offline/providers/Microsoft.Network/privateDnsZones/privatelink.postgres.database.azure.com"
}

variable "administrator_login" {
  type    = string
  default = "parkio_pg_admin"
}

variable "create_azure_resources" {
  type    = bool
  default = false
  validation {
    condition     = var.create_azure_resources == false
    error_message = "create_azure_resources must be false for staging during IAC-01."
  }
}

variable "apply_authorized" {
  type    = bool
  default = false
  validation {
    condition     = var.apply_authorized == false
    error_message = "apply_authorized must be false for staging during IAC-01."
  }
}

variable "tags" {
  type = map(string)
  default = {
    parkio_package = "pp-01b-iac-01"
    environment    = "staging"
  }
}
