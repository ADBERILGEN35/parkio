# Network + Private DNS (M-NET-DNS) — PP-01B-IAC-02
#
# Frozen model: Flexible Server private VNet integration (delegated subnet),
# NOT Private Endpoint / Private Link.
# Private DNS zone MUST end with .postgres.database.azure.com and MUST NOT be
# privatelink.postgres.database.azure.com.

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
  type = string
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
  description = "When true, create disposable VNet/subnets/DNS. When false, use existing IDs."
  default     = false
}

variable "existing_vnet_id" {
  type     = string
  default  = null
  nullable = true
}

variable "existing_delegated_subnet_id" {
  type     = string
  default  = null
  nullable = true
}

variable "existing_probe_subnet_id" {
  type     = string
  default  = null
  nullable = true
}

variable "existing_private_dns_zone_id" {
  type     = string
  default  = null
  nullable = true
}

variable "sandbox_address_space" {
  type        = list(string)
  description = "VNet address space (Mode B default 10.251.0.0/16)."
  default     = ["10.251.0.0/16"]
}

variable "postgres_subnet_prefixes" {
  type        = list(string)
  description = "Delegated PostgreSQL subnet prefixes (Mode B default 10.251.1.0/24)."
  default     = ["10.251.1.0/24"]
}

variable "probe_subnet_prefixes" {
  type        = list(string)
  description = "Non-delegated probe subnet prefixes (Mode B default 10.251.2.0/24)."
  default     = ["10.251.2.0/24"]
}

variable "private_dns_zone_name" {
  type        = string
  description = "VNet-integration Private DNS zone ending with .postgres.database.azure.com (not privatelink)."
  default     = "pp01b-mb-20260804-7nr2.private.postgres.database.azure.com"

  validation {
    condition = (
      endswith(var.private_dns_zone_name, ".postgres.database.azure.com") &&
      var.private_dns_zone_name != "privatelink.postgres.database.azure.com" &&
      !startswith(var.private_dns_zone_name, "privatelink.")
    )
    error_message = "private_dns_zone_name must end with .postgres.database.azure.com and must not be the Private Link zone privatelink.postgres.database.azure.com."
  }
}

variable "tags" {
  type    = map(string)
  default = {}
}

locals {
  # Reject hosted-beta CIDR literals when creating disposable network.
  postgres_probe_prefix_overlap = length(setintersection(
    toset(var.postgres_subnet_prefixes),
    toset(var.probe_subnet_prefixes)
  )) > 0

  uses_hosted_beta_literal = anytrue([
    for c in concat(var.sandbox_address_space, var.postgres_subnet_prefixes, var.probe_subnet_prefixes) :
    startswith(c, "10.0.")
  ])
}

check "reject_hosted_beta_cidrs_on_create" {
  assert {
    condition     = !var.create_network || !local.uses_hosted_beta_literal
    error_message = "Disposable Mode B network must not use hosted-beta 10.0.0.0/16 ranges."
  }
}

check "reject_subnet_prefix_overlap" {
  assert {
    condition     = !var.create_network || !local.postgres_probe_prefix_overlap
    error_message = "PostgreSQL delegated subnet and probe subnet prefixes must not overlap."
  }
}

resource "azurerm_virtual_network" "this" {
  count               = var.create_network ? 1 : 0
  name                = "vnet-${var.naming_prefix}"
  location            = var.location
  resource_group_name = var.resource_group_name
  address_space       = var.sandbox_address_space
  tags                = var.tags
}

resource "azurerm_subnet" "postgres" {
  count                = var.create_network ? 1 : 0
  name                 = "snet-pg-delegated"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.this[0].name
  address_prefixes     = var.postgres_subnet_prefixes

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

resource "azurerm_subnet" "probe" {
  count                = var.create_network ? 1 : 0
  name                 = "snet-probe"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.this[0].name
  address_prefixes     = var.probe_subnet_prefixes
  # Intentionally NO PostgreSQL delegation — probe VM only.
}

resource "azurerm_private_dns_zone" "postgres" {
  count               = var.create_network ? 1 : 0
  name                = var.private_dns_zone_name
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "postgres" {
  count                 = var.create_network ? 1 : 0
  name                  = "pdnslink-${var.naming_prefix}"
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
  probe_subnet_id = (
    var.create_network
    ? azurerm_subnet.probe[0].id
    : var.existing_probe_subnet_id
  )
  private_dns_zone_id = (
    var.create_network
    ? azurerm_private_dns_zone.postgres[0].id
    : var.existing_private_dns_zone_id
  )
  private_dns_zone_name_effective = (
    var.create_network
    ? azurerm_private_dns_zone.postgres[0].name
    : var.private_dns_zone_name
  )
}

check "network_inputs_present" {
  assert {
    condition = (
      !var.create_network || (
        local.vnet_id != null && local.vnet_id != "" &&
        local.subnet_id != null && local.subnet_id != "" &&
        local.probe_subnet_id != null && local.probe_subnet_id != "" &&
        local.private_dns_zone_id != null && local.private_dns_zone_id != ""
      )
      ) && (
      var.create_network || (
        try(length(var.existing_vnet_id), 0) > 0 &&
        try(length(var.existing_delegated_subnet_id), 0) > 0 &&
        try(length(var.existing_private_dns_zone_id), 0) > 0
      )
    )
    error_message = "VNet, delegated subnet, probe subnet (when creating), and Private DNS zone IDs are required."
  }
}

check "dns_not_privatelink" {
  assert {
    condition = (
      local.private_dns_zone_name_effective != "privatelink.postgres.database.azure.com" &&
      !startswith(local.private_dns_zone_name_effective, "privatelink.")
    )
    error_message = "Private Link DNS zone is forbidden for VNet-integration Model A."
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

output "probe_subnet_id" {
  value       = local.probe_subnet_id
  description = "Non-delegated probe subnet ID for the disposable validation VM."
}

output "private_dns_zone_id" {
  value       = local.private_dns_zone_id
  description = "Private DNS zone ID for VNet-integration Flexible Server access."
}

output "private_dns_zone_name" {
  value       = local.private_dns_zone_name_effective
  description = "Selected VNet-integration Private DNS zone name."
}

output "postgres_delegation_count" {
  value       = var.create_network ? 1 : 0
  description = "Number of PostgreSQL Flexible Server subnet delegations created."
}

output "subnet_create_count" {
  value       = var.create_network ? 2 : 0
  description = "Number of subnets created (delegated + probe)."
}

output "public_ingress_exposed" {
  value       = false
  description = "Contract: network module never exposes public DB ingress."
}

output "cleanup_inventory" {
  value = var.create_network ? [
    "azurerm_private_dns_zone_virtual_network_link.postgres",
    "azurerm_postgresql_flexible_server (external dependency)",
    "azurerm_subnet.probe",
    "azurerm_subnet.postgres",
    "azurerm_private_dns_zone.postgres",
    "azurerm_virtual_network.this",
  ] : []
}
