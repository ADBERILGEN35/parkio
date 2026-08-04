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

  mode_b_auth_exact = "PP-01B-MODE-B-20260804-01"
  unlock_inputs_complete = (
    var.authorization_reference == local.mode_b_auth_exact &&
    var.cost_approval_reference != null && var.cost_approval_reference != "" &&
    var.sandbox_cleanup_deadline != null && var.sandbox_cleanup_deadline != "" &&
    length(var.naming_prefix) > 0 &&
    var.environment == "sandbox"
  )

  mode_b_tags = merge(var.tags, {
    authorization    = coalesce(var.authorization_reference, "locked")
    cleanup_deadline = coalesce(var.sandbox_cleanup_deadline, "unset")
    naming_prefix    = var.naming_prefix
  })
}

# --- Mode B apply-unlock contract (defaults locked) ---

check "apply_unlock_requires_auth_bundle" {
  assert {
    condition     = !var.apply_authorized || local.unlock_inputs_complete
    error_message = "apply_authorized=true requires authorization_reference=PP-01B-MODE-B-20260804-01, cost_approval_reference, sandbox_cleanup_deadline, naming_prefix, environment=sandbox."
  }
}

check "create_network_requires_apply_authorized" {
  assert {
    condition     = !var.create_network || var.apply_authorized
    error_message = "create_network requires apply_authorized."
  }
}

check "create_rg_requires_apply_authorized" {
  assert {
    condition     = !var.create_disposable_rg || var.apply_authorized
    error_message = "create_disposable_rg requires apply_authorized."
  }
}

check "create_probe_requires_apply_authorized" {
  assert {
    condition     = !var.create_probe || var.apply_authorized
    error_message = "create_probe requires apply_authorized."
  }
}

check "create_azure_requires_unlock_and_provider" {
  assert {
    condition = (
      !var.create_azure_resources || (
        var.apply_authorized &&
        var.provider_postgresql_registered &&
        (var.create_network || (var.existing_delegated_subnet_id != null && var.existing_delegated_subnet_id != ""))
      )
    )
    error_message = "create_azure_resources requires apply_authorized, provider_postgresql_registered=true, and network inputs."
  }
}

check "live_bootstrap_requires_servers" {
  assert {
    condition = (
      !var.enable_live_bootstrap || (
        var.create_azure_resources &&
        var.environment == "sandbox" &&
        var.apply_authorized
      )
    )
    error_message = "enable_live_bootstrap requires sandbox, apply_authorized, and create_azure_resources."
  }
}

check "reject_hosted_beta_identifiers" {
  assert {
    condition = (
      !can(regex("hosted-beta", var.resource_group_name)) &&
      !can(regex("hosted-beta", coalesce(var.existing_vnet_id, ""))) &&
      !can(regex("hosted-beta", coalesce(var.existing_delegated_subnet_id, "")))
    )
    error_message = "hosted-beta identifiers are forbidden in Mode B sandbox inputs."
  }
}

module "disposable_rg" {
  source = "../../modules/disposable-rg"

  create                  = var.create_disposable_rg
  name                    = var.resource_group_name
  location                = var.location
  environment             = var.environment
  authorization_reference = coalesce(var.authorization_reference, "locked")
  cleanup_deadline        = var.sandbox_cleanup_deadline
  tags                    = local.mode_b_tags
}

module "network" {
  source = "../../modules/network-dns"

  naming_prefix                = var.naming_prefix
  location                     = var.location
  resource_group_name          = module.disposable_rg.name
  create_network               = var.create_network
  existing_vnet_id             = var.existing_vnet_id
  existing_delegated_subnet_id = var.existing_delegated_subnet_id
  existing_probe_subnet_id     = var.existing_probe_subnet_id
  existing_private_dns_zone_id = var.existing_private_dns_zone_id
  sandbox_address_space        = var.sandbox_address_space
  postgres_subnet_prefixes     = var.postgres_subnet_prefixes
  probe_subnet_prefixes        = var.probe_subnet_prefixes
  private_dns_zone_name        = var.private_dns_zone_name
  tags                         = local.mode_b_tags
}

module "core" {
  source = "../../modules/postgresql-flexible-server"

  name                          = "psql-${var.naming_prefix}-core"
  resource_group_name           = module.disposable_rg.name
  location                      = var.location
  postgres_version              = var.postgres_version
  sku_name                      = var.core_sku_name
  storage_mb                    = var.core_storage_mb
  backup_retention_days         = var.core_backup_retention_days
  ha_mode                       = var.core_ha_mode
  environment                   = "sandbox"
  production_shaped             = false
  public_network_access_enabled = false
  delegated_subnet_id           = coalesce(module.network.delegated_subnet_id, "offline-placeholder")
  private_dns_zone_id           = coalesce(module.network.private_dns_zone_id, "offline-placeholder")
  administrator_login           = var.administrator_login
  administrator_password        = var.administrator_password
  deletion_protection           = var.deletion_protection
  create_resource               = var.create_azure_resources
  cluster_role                  = "core"
  tags                          = local.mode_b_tags
}

module "parking" {
  source = "../../modules/postgresql-flexible-server"

  name                          = "psql-${var.naming_prefix}-parking"
  resource_group_name           = module.disposable_rg.name
  location                      = var.location
  postgres_version              = var.postgres_version
  sku_name                      = var.parking_sku_name
  storage_mb                    = var.parking_storage_mb
  backup_retention_days         = var.parking_backup_retention_days
  ha_mode                       = var.parking_ha_mode
  environment                   = "sandbox"
  production_shaped             = false
  public_network_access_enabled = false
  delegated_subnet_id           = coalesce(module.network.delegated_subnet_id, "offline-placeholder")
  private_dns_zone_id           = coalesce(module.network.private_dns_zone_id, "offline-placeholder")
  administrator_login           = var.administrator_login
  administrator_password        = var.administrator_password
  deletion_protection           = var.deletion_protection
  create_resource               = var.create_azure_resources
  cluster_role                  = "parking"
  tags                          = local.mode_b_tags
}

module "probe" {
  source = "../../modules/mode-b-probe"

  create                       = var.create_probe
  environment                  = var.environment
  name                         = "vm-${var.naming_prefix}-probe"
  location                     = var.location
  resource_group_name          = module.disposable_rg.name
  probe_subnet_id              = coalesce(module.network.probe_subnet_id, "offline-probe-subnet")
  delegated_postgres_subnet_id = coalesce(module.network.delegated_subnet_id, "")
  vm_size                      = var.probe_vm_size
  ssh_public_key               = var.probe_ssh_public_key
  tags                         = local.mode_b_tags
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

  environment                    = "sandbox"
  server_count                   = 2
  database_count                 = 10
  role_count                     = 10
  service_manifest               = local.service_manifest
  postgis_on_core                = false
  postgis_on_parking             = var.enable_postgis
  public_network_access_enabled  = false
  core_backup_retention_days     = var.core_backup_retention_days
  parking_backup_retention_days  = var.parking_backup_retention_days
  core_ha_mode                   = var.core_ha_mode
  parking_ha_mode                = var.parking_ha_mode
  core_sku_name                  = var.core_sku_name
  parking_sku_name               = var.parking_sku_name
  production_shaped              = false
  apply_authorized               = var.apply_authorized
  tls_client_mode                = "verify-full"
  provider_topology              = local.provider_topology
  private_dns_zone_name          = var.private_dns_zone_name
  disposable_rg_created          = var.create_disposable_rg
  probe_subnet_present           = var.create_network || (var.existing_probe_subnet_id != null && var.existing_probe_subnet_id != "")
  probe_public_ip_count          = module.probe.public_ip_count
  probe_inbound_ssh_rule_count   = module.probe.inbound_ssh_rule_count
  authorization_reference        = var.authorization_reference
  sandbox_cleanup_deadline       = var.sandbox_cleanup_deadline
  create_azure_resources         = var.create_azure_resources
  provider_postgresql_registered = var.provider_postgresql_registered
  mode_b_create_shaped           = var.apply_authorized && var.create_disposable_rg && var.create_network
}
