# Mode B private probe VM — PP-01B-IAC-02
#
# Temporary Linux VM for DNS/TLS/PostgreSQL/PostGIS validation via Azure Run Command.
# No public IP, no inbound SSH, no committed keys/passwords.

terraform {
  required_version = ">= 1.5.0, < 2.0.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.26"
    }
  }
}

variable "create" {
  type    = bool
  default = false
}

variable "environment" {
  type = string
  validation {
    condition     = var.environment == "sandbox"
    error_message = "Probe VM is sandbox-only."
  }
}

variable "name" {
  type = string
  validation {
    condition     = can(regex("^vm-[a-z0-9-]+$", var.name)) && !can(regex("hosted-beta", var.name))
    error_message = "Probe VM name must be vm-<prefix> and must not reference hosted-beta."
  }
}

variable "location" {
  type = string
}

variable "resource_group_name" {
  type = string
  validation {
    condition     = !can(regex("hosted-beta", var.resource_group_name))
    error_message = "Probe must not use hosted-beta resource group."
  }
}

variable "probe_subnet_id" {
  type = string
  validation {
    condition     = length(trimspace(var.probe_subnet_id)) > 0
    error_message = "probe_subnet_id is required when creating probe (or offline placeholder)."
  }
}

variable "delegated_postgres_subnet_id" {
  type        = string
  description = "Delegated PostgreSQL subnet ID — probe MUST NOT use this."
  default     = ""
}

variable "vm_size" {
  type        = string
  description = "Smallest suitable temporary Linux SKU."
  default     = "Standard_B1s"
}

variable "admin_username" {
  type    = string
  default = "parkio_probe"
}

variable "ssh_public_key" {
  type        = string
  description = "Ephemeral public key only (never commit private key). Required when create=true."
  default     = null
  nullable    = true
  sensitive   = true
}

variable "tags" {
  type    = map(string)
  default = {}
}

locals {
  probe_uses_delegated = (
    length(var.delegated_postgres_subnet_id) > 0 &&
    var.probe_subnet_id == var.delegated_postgres_subnet_id
  )
}

check "probe_not_on_delegated_subnet" {
  assert {
    condition     = !var.create || !local.probe_uses_delegated
    error_message = "Probe VM must not be placed on the PostgreSQL delegated subnet."
  }
}

check "ssh_public_key_when_create" {
  assert {
    condition = (
      !var.create ||
      (var.ssh_public_key != null && var.ssh_public_key != "")
    )
    error_message = "ssh_public_key required when create=true (public half only; private key never committed)."
  }
}

resource "azurerm_network_interface" "probe" {
  count               = var.create ? 1 : 0
  name                = "nic-${var.name}"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags

  ip_configuration {
    name                          = "internal"
    subnet_id                     = var.probe_subnet_id
    private_ip_address_allocation = "Dynamic"
    # No public_ip_address_id — public IP count must remain 0.
  }
}

resource "azurerm_linux_virtual_machine" "probe" {
  count               = var.create ? 1 : 0
  name                = var.name
  location            = var.location
  resource_group_name = var.resource_group_name
  size                = var.vm_size
  admin_username      = var.admin_username
  network_interface_ids = [
    azurerm_network_interface.probe[0].id,
  ]
  disable_password_authentication = true
  # No custom_data secrets. Run Command used later for validation.
  # System-assigned identity enables Azure Run Command without SSH.
  identity {
    type = "SystemAssigned"
  }

  admin_ssh_key {
    username   = var.admin_username
    public_key = var.ssh_public_key
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Standard_LRS"
    disk_size_gb         = 30
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "0001-com-ubuntu-server-jammy"
    sku       = "22_04-lts-gen2"
    version   = "latest"
  }

  tags = merge(var.tags, {
    role      = "mode-b-probe"
    temporary = "true"
  })
}

# Explicit: zero public IPs and zero SSH NSG rules owned by this module.
# (No azurerm_public_ip, no azurerm_network_security_rule for 22.)

output "vm_id" {
  value = try(azurerm_linux_virtual_machine.probe[0].id, null)
}

output "nic_id" {
  value = try(azurerm_network_interface.probe[0].id, null)
}

output "public_ip_count" {
  value = 0
}

output "inbound_ssh_rule_count" {
  value = 0
}

output "uses_probe_subnet" {
  value = true
}

output "created" {
  value = var.create
}

output "cleanup_inventory" {
  value = var.create ? [
    "azurerm_linux_virtual_machine.probe",
    "azurerm_network_interface.probe",
  ] : []
}
