# Production stack — static guarded definition only.
# Apply is structurally impossible: apply_authorized cannot be true;
# create_azure_resources cannot be true; no backend; no credentials.

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
}

provider "azurerm" {
  features {}
}

provider "postgresql" {
  host             = "offline.invalid"
  username         = "offline"
  password         = "offline-not-for-apply"
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
  public_network_access_enabled = var.public_network_access_enabled
}

module "network" {
  source = "../../modules/network-dns"

  naming_prefix                = var.naming_prefix
  location                     = var.location
  resource_group_name          = var.resource_group_name
  create_network               = false
  existing_vnet_id             = var.existing_vnet_id
  existing_delegated_subnet_id = var.existing_delegated_subnet_id
  existing_probe_subnet_id     = var.existing_probe_subnet_id
  existing_private_dns_zone_id = var.existing_private_dns_zone_id
  private_dns_zone_name        = var.private_dns_zone_name
  tags                         = var.tags
}

module "core" {
  source = "../../modules/postgresql-flexible-server"

  name                   = "${var.naming_prefix}-core-pg"
  resource_group_name    = var.resource_group_name
  location               = var.location
  postgres_version       = var.postgres_version
  sku_name               = var.core_sku_name
  storage_mb             = var.core_storage_mb
  backup_retention_days  = var.core_backup_retention_days
  ha_mode                = var.core_ha_mode
  environment            = "production"
  production_shaped      = true
  delegated_subnet_id    = module.network.delegated_subnet_id
  private_dns_zone_id    = module.network.private_dns_zone_id
  administrator_login    = var.administrator_login
  administrator_password = null
  deletion_protection    = true
  create_resource        = false
  cluster_role           = "core"
  tags                   = var.tags
}

module "parking" {
  source = "../../modules/postgresql-flexible-server"

  name                   = "${var.naming_prefix}-parking-pg"
  resource_group_name    = var.resource_group_name
  location               = var.location
  postgres_version       = var.postgres_version
  sku_name               = var.parking_sku_name
  storage_mb             = var.parking_storage_mb
  backup_retention_days  = var.parking_backup_retention_days
  ha_mode                = var.parking_ha_mode
  environment            = "production"
  production_shaped      = true
  delegated_subnet_id    = module.network.delegated_subnet_id
  private_dns_zone_id    = module.network.private_dns_zone_id
  administrator_login    = var.administrator_login
  administrator_password = null
  deletion_protection    = true
  create_resource        = false
  cluster_role           = "parking"
  tags                   = var.tags
}

module "core_roles" {
  source                = "../../modules/database-roles"
  cluster               = "core"
  enable_live_bootstrap = false
}

module "parking_roles" {
  source                = "../../modules/database-roles"
  cluster               = "parking"
  enable_live_bootstrap = false
}

module "postgis" {
  source                = "../../modules/postgis-bootstrap"
  cluster               = "parking"
  enable_live_bootstrap = false
  enable_postgis        = true
}

module "policy" {
  source = "../../modules/policy-guards"

  environment                   = "production"
  server_count                  = 2
  database_count                = 10
  role_count                    = 10
  service_manifest              = local.service_manifest
  postgis_on_core               = false
  postgis_on_parking            = true
  public_network_access_enabled = false
  core_backup_retention_days    = var.core_backup_retention_days
  parking_backup_retention_days = var.parking_backup_retention_days
  core_ha_mode                  = var.core_ha_mode
  parking_ha_mode               = var.parking_ha_mode
  core_sku_name                 = var.core_sku_name
  parking_sku_name              = var.parking_sku_name
  production_shaped             = true
  apply_authorized              = var.apply_authorized
  tls_client_mode               = "verify-full"
  provider_topology             = "azure-postgresql-flexible-server-vnet-private-dns"
  private_dns_zone_name         = var.private_dns_zone_name
}

check "production_apply_structurally_forbidden" {
  assert {
    condition = (
      var.apply_authorized == false &&
      var.create_azure_resources == false &&
      var.production_enablement == false
    )
    error_message = "Production apply/enablement is forbidden in PP-01B-IAC-01."
  }
}
