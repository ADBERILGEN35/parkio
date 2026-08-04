# Network + Private DNS (M-NET-DNS)
#
# Models Flexible Server private access (VNet integration / delegated subnet)
# + Private DNS. Does not invent production CIDRs or subscription IDs.

terraform {
  required_version = ">= 1.5.0, < 2.0.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.26"
    }
  }
}

variable "naming_prefix" {
  type        = string
  description = "Temporary/sanitized prefix for created network resources."
  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$", var.naming_prefix))
    error_message = "naming_prefix must be lowercase alphanumeric/hyphen, max 32."
  }
}

variable "location" {
  type        = string
  description = "Azure location for created network resources."
  validation {
    condition     = length(trimspace(var.location)) > 0
    error_message = "location must be non-empty."
  }
}

variable "resource_group_name" {
  type        = string
  description = "Resource group for created network/DNS resources."
}

variable "create_network" {
  type        = bool
  description = "When true, create a disposable sandbox VNet/subnet/DNS. When false, use existing IDs."
  default     = false
}

variable "existing_vnet_id" {
  type        = string
  description = "Existing VNet ID when create_network=false."
  default     = null
  nullable    = true
}

variable "existing_delegated_subnet_id" {
  type        = string
  description = "Existing delegated subnet ID when create_network=false."
  default     = null
  nullable    = true
}

variable "existing_private_dns_zone_id" {
  type        = string
  description = "Existing Private DNS zone ID when create_network=false."
  default     = null
  nullable    = true
}

variable "sandbox_address_space" {
  type        = list(string)
  description = "Sanitized example CIDRs for create_network=true only (not production)."
  default     = ["10.250.0.0/16"]
}

variable "sandbox_subnet_prefix" {
  type        = list(string)
  description = "Sanitized delegated subnet prefixes for create_network=true only."
  default     = ["10.250.1.0/24"]
}

variable "tags" {
  type    = map(string)
  default = {}
}

locals {
  private_dns_zone_name = "privatelink.postgres.database.azure.com"
}

resource "azurerm_virtual_network" "this" {
  count               = var.create_network ? 1 : 0
  name                = "${var.naming_prefix}-vnet"
  location            = var.location
  resource_group_name = var.resource_group_name
  address_space       = var.sandbox_address_space
  tags                = var.tags
}

resource "azurerm_subnet" "postgres" {
  count                = var.create_network ? 1 : 0
  name                 = "${var.naming_prefix}-pg-delegated"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.this[0].name
  address_prefixes     = var.sandbox_subnet_prefix

  delegation {
    name = "fs"
    service_delegation {
      name = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = [
        "Microsoft.Network/virtualNetworks/subnets/join/action",
      ]
    }
  }
}

resource "azurerm_private_dns_zone" "postgres" {
  count               = var.create_network ? 1 : 0
  name                = local.private_dns_zone_name
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "postgres" {
  count                 = var.create_network ? 1 : 0
  name                  = "${var.naming_prefix}-pg-dns-link"
  private_dns_zone_name = azurerm_private_dns_zone.postgres[0].name
  virtual_network_id    = azurerm_virtual_network.this[0].id
  resource_group_name   = var.resource_group_name
  tags                  = var.tags
}

locals {
  vnet_id = var.create_network ? azurerm_virtual_network.this[0].id : var.existing_vnet_id
  subnet_id = (
    var.create_network
    ? azurerm_subnet.postgres[0].id
    : var.existing_delegated_subnet_id
  )
  private_dns_zone_id = (
    var.create_network
    ? azurerm_private_dns_zone.postgres[0].id
    : var.existing_private_dns_zone_id
  )
}

check "network_inputs_present" {
  assert {
    condition = (
      local.vnet_id != null && local.vnet_id != "" &&
      local.subnet_id != null && local.subnet_id != "" &&
      local.private_dns_zone_id != null && local.private_dns_zone_id != ""
    )
    error_message = "VNet, delegated subnet, and Private DNS zone IDs are required (create or existing)."
  }
}

output "vnet_id" {
  value       = local.vnet_id
  description = "Virtual network ID used for private Flexible Server access."
}

output "delegated_subnet_id" {
  value       = local.subnet_id
  description = "Delegated subnet ID for Microsoft.DBforPostgreSQL/flexibleServers."
}

output "private_dns_zone_id" {
  value       = local.private_dns_zone_id
  description = "Private DNS zone ID for postgres.database.azure.com family."
}

output "private_dns_zone_name" {
  value       = local.private_dns_zone_name
  description = "Canonical Private DNS zone name for Flexible Server private access."
}

output "public_ingress_exposed" {
  value       = false
  description = "Contract: network module never exposes public DB ingress."
}
