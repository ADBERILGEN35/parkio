terraform {
  required_version = ">= 1.5.0, < 2.0.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.26"
    }
    postgresql = {
      source  = "cyrilgdn/postgresql"
      version = "~> 1.25"
    }
  }

  # Remote backend is supplied externally before any shared apply.
  # No backend block here — local state only (gitignored) during authoring.
}

provider "azurerm" {
  features {}
  # No credentials, subscription_id, or tenant_id in this block.
}

# Offline bootstrap provider: never used when enable_live_bootstrap=false (resource count=0).
provider "postgresql" {
  host             = coalesce(var.bootstrap_host, "offline.invalid")
  port             = 5432
  username         = coalesce(var.bootstrap_username, "offline")
  password         = coalesce(var.bootstrap_password, "offline-not-for-apply")
  sslmode          = "disable"
  superuser        = false
  connect_timeout  = 5
  expected_version = "16.0"
}

locals {
  service_manifest = {
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

  provider_topology             = "azure-postgresql-flexible-server-vnet-private-dns"
  public_network_access_enabled = var.public_network_access_enabled
  sandbox_cleanup_deadline      = var.sandbox_cleanup_deadline
  diagnostics_placeholder       = var.enable_diagnostics_placeholder
}

module "network" {
  source = "../../modules/network-dns"

  naming_prefix                = var.naming_prefix
  location                     = var.location
  resource_group_name          = var.resource_group_name
  create_network               = var.create_network
  existing_vnet_id             = var.existing_vnet_id
  existing_delegated_subnet_id = var.existing_delegated_subnet_id
  existing_private_dns_zone_id = var.existing_private_dns_zone_id
  sandbox_address_space        = var.sandbox_address_space
  sandbox_subnet_prefix        = var.sandbox_subnet_prefix
  tags                         = var.tags
}

module "core" {
  source = "../../modules/postgresql-flexible-server"

  name                          = "${var.naming_prefix}-core-pg"
  resource_group_name           = var.resource_group_name
  location                      = var.location
  postgres_version              = var.postgres_version
  sku_name                      = var.core_sku_name
  storage_mb                    = var.core_storage_mb
  backup_retention_days         = var.core_backup_retention_days
  ha_mode                       = var.core_ha_mode
  environment                   = "sandbox"
  production_shaped             = false
  public_network_access_enabled = false
  delegated_subnet_id           = module.network.delegated_subnet_id
  private_dns_zone_id           = module.network.private_dns_zone_id
  administrator_login           = var.administrator_login
  administrator_password        = var.administrator_password
  deletion_protection           = var.deletion_protection
  create_resource               = var.create_azure_resources
  cluster_role                  = "core"
  tags                          = var.tags
}

module "parking" {
  source = "../../modules/postgresql-flexible-server"

  name                          = "${var.naming_prefix}-parking-pg"
  resource_group_name           = var.resource_group_name
  location                      = var.location
  postgres_version              = var.postgres_version
  sku_name                      = var.parking_sku_name
  storage_mb                    = var.parking_storage_mb
  backup_retention_days         = var.parking_backup_retention_days
  ha_mode                       = var.parking_ha_mode
  environment                   = "sandbox"
  production_shaped             = false
  public_network_access_enabled = false
  delegated_subnet_id           = module.network.delegated_subnet_id
  private_dns_zone_id           = module.network.private_dns_zone_id
  administrator_login           = var.administrator_login
  administrator_password        = var.administrator_password
  deletion_protection           = var.deletion_protection
  create_resource               = var.create_azure_resources
  cluster_role                  = "parking"
  tags                          = var.tags
}

module "core_roles" {
  source = "../../modules/database-roles"

  cluster                = "core"
  enable_live_bootstrap  = var.enable_live_bootstrap
  service_role_passwords = var.service_role_passwords
}

module "parking_roles" {
  source = "../../modules/database-roles"

  cluster                = "parking"
  enable_live_bootstrap  = var.enable_live_bootstrap
  service_role_passwords = var.service_role_passwords
}

module "postgis" {
  source = "../../modules/postgis-bootstrap"

  cluster               = "parking"
  enable_live_bootstrap = var.enable_live_bootstrap
  enable_postgis        = var.enable_postgis
  target_database       = "parkio_parking"
}

module "policy" {
  source = "../../modules/policy-guards"

  environment                   = "sandbox"
  server_count                  = 2
  database_count                = 10
  role_count                    = 10
  service_manifest              = local.service_manifest
  postgis_on_core               = false
  postgis_on_parking            = var.enable_postgis
  public_network_access_enabled = false
  core_backup_retention_days    = var.core_backup_retention_days
  parking_backup_retention_days = var.parking_backup_retention_days
  core_ha_mode                  = var.core_ha_mode
  parking_ha_mode               = var.parking_ha_mode
  core_sku_name                 = var.core_sku_name
  parking_sku_name              = var.parking_sku_name
  production_shaped             = false
  apply_authorized              = false
  tls_client_mode               = "verify-full"
  provider_topology             = local.provider_topology
}

check "cost_approval_before_create" {
  assert {
    condition = (
      !var.create_azure_resources ||
      (var.cost_approval_reference != null && length(trimspace(var.cost_approval_reference)) > 0)
    )
    error_message = "cost_approval_reference is required before any future sandbox create/apply."
  }
}

check "apply_not_authorized_in_iac01" {
  assert {
    condition     = var.apply_authorized == false
    error_message = "PP-01B-IAC-01 forbids apply_authorized=true (Azure sandbox apply not authorized in this package)."
  }
}
