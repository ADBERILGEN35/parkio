# Disposable Mode B resource group (sandbox only) — PP-01B-IAC-02

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
  type        = bool
  description = "Create disposable RG. Must be false unless Mode B apply is authorized."
  default     = false
}

variable "name" {
  type = string
  validation {
    condition     = can(regex("^rg-[a-z0-9-]+$", var.name)) && !can(regex("hosted-beta", var.name))
    error_message = "RG name must be rg-<prefix> and must not reference hosted-beta."
  }
}

variable "location" {
  type = string
}

variable "environment" {
  type = string
  validation {
    condition     = var.environment == "sandbox"
    error_message = "Disposable Mode B RG is sandbox-only."
  }
}

variable "authorization_reference" {
  type = string
}

variable "cleanup_deadline" {
  type     = string
  nullable = true
  default  = null
}

variable "tags" {
  type    = map(string)
  default = {}
}

check "create_requires_auth_ref" {
  assert {
    condition = (
      !var.create ||
      var.authorization_reference == "PP-01B-MODE-B-20260804-01"
    )
    error_message = "Disposable RG create requires authorization PP-01B-MODE-B-20260804-01."
  }
}

check "create_requires_cleanup_deadline" {
  assert {
    condition = (
      !var.create ||
      (var.cleanup_deadline != null && var.cleanup_deadline != "")
    )
    error_message = "cleanup_deadline is required when creating disposable Mode B RG."
  }
}

resource "azurerm_resource_group" "mode_b" {
  count    = var.create ? 1 : 0
  name     = var.name
  location = var.location
  tags = merge(var.tags, {
    environment      = var.environment
    program          = "pp-01b"
    package          = "mode-b"
    temporary        = "true"
    authorization    = var.authorization_reference
    cleanup_deadline = coalesce(var.cleanup_deadline, "unset")
  })
}

output "name" {
  value = var.create ? azurerm_resource_group.mode_b[0].name : var.name
}

output "id" {
  value = try(azurerm_resource_group.mode_b[0].id, null)
}

output "location" {
  value = var.create ? azurerm_resource_group.mode_b[0].location : var.location
}

output "created" {
  value = var.create
}

output "cleanup_inventory" {
  value = var.create ? ["azurerm_resource_group.mode_b"] : []
}
