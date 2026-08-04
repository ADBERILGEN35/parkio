# Terraform native tests for M-POLICY-GUARDS (offline, no Azure).

run "canonical_topology_passes" {
  command = plan

  variables {
    environment                   = "sandbox"
    server_count                  = 2
    database_count                = 10
    role_count                    = 10
    postgis_on_core               = false
    postgis_on_parking            = true
    public_network_access_enabled = false
    core_backup_retention_days    = 7
    parking_backup_retention_days = 7
    core_ha_mode                  = "Disabled"
    parking_ha_mode               = "Disabled"
    core_sku_name                 = "GP_Standard_D2s_v3"
    parking_sku_name              = "GP_Standard_D2s_v3"
    production_shaped             = false
    apply_authorized              = false
    tls_client_mode               = "verify-full"
    provider_topology             = "azure-postgresql-flexible-server-vnet-private-dns"
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
  }

  assert {
    condition     = output.policy_passed == true
    error_message = "canonical policy should pass"
  }
}

run "reject_wrong_server_count" {
  command = plan

  variables {
    environment                   = "sandbox"
    server_count                  = 1
    database_count                = 10
    role_count                    = 10
    postgis_on_core               = false
    postgis_on_parking            = true
    public_network_access_enabled = false
    core_backup_retention_days    = 7
    parking_backup_retention_days = 7
    core_ha_mode                  = "Disabled"
    parking_ha_mode               = "Disabled"
    core_sku_name                 = "GP_Standard_D2s_v3"
    parking_sku_name              = "GP_Standard_D2s_v3"
    production_shaped             = false
    apply_authorized              = false
    tls_client_mode               = "verify-full"
    provider_topology             = "azure-postgresql-flexible-server-vnet-private-dns"
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
  }

  expect_failures = [check.server_count_is_two]
}

run "reject_postgis_on_core" {
  command = plan

  variables {
    environment                   = "sandbox"
    server_count                  = 2
    database_count                = 10
    role_count                    = 10
    postgis_on_core               = true
    postgis_on_parking            = true
    public_network_access_enabled = false
    core_backup_retention_days    = 7
    parking_backup_retention_days = 7
    core_ha_mode                  = "Disabled"
    parking_ha_mode               = "Disabled"
    core_sku_name                 = "GP_Standard_D2s_v3"
    parking_sku_name              = "GP_Standard_D2s_v3"
    production_shaped             = false
    apply_authorized              = false
    tls_client_mode               = "verify-full"
    provider_topology             = "azure-postgresql-flexible-server-vnet-private-dns"
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
  }

  expect_failures = [check.postgis_not_on_core]
}

run "reject_production_apply" {
  command = plan

  variables {
    environment                   = "production"
    server_count                  = 2
    database_count                = 10
    role_count                    = 10
    postgis_on_core               = false
    postgis_on_parking            = true
    public_network_access_enabled = false
    core_backup_retention_days    = 30
    parking_backup_retention_days = 30
    core_ha_mode                  = "ZoneRedundant"
    parking_ha_mode               = "ZoneRedundant"
    core_sku_name                 = "GP_Standard_D4s_v3"
    parking_sku_name              = "GP_Standard_D4s_v3"
    production_shaped             = true
    apply_authorized              = true
    authorization_reference       = "PP-01B-MODE-B-20260804-01"
    sandbox_cleanup_deadline      = "2026-08-05T00:00:00Z"
    tls_client_mode               = "verify-full"
    provider_topology             = "azure-postgresql-flexible-server-vnet-private-dns"
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
  }

  expect_failures = [check.production_apply_forbidden]
}

run "reject_burstable_with_ha" {
  command = plan

  variables {
    environment                   = "sandbox"
    server_count                  = 2
    database_count                = 10
    role_count                    = 10
    postgis_on_core               = false
    postgis_on_parking            = true
    public_network_access_enabled = false
    core_backup_retention_days    = 7
    parking_backup_retention_days = 7
    core_ha_mode                  = "ZoneRedundant"
    parking_ha_mode               = "Disabled"
    core_sku_name                 = "B_Standard_B1ms"
    parking_sku_name              = "GP_Standard_D2s_v3"
    production_shaped             = false
    apply_authorized              = false
    tls_client_mode               = "verify-full"
    provider_topology             = "azure-postgresql-flexible-server-vnet-private-dns"
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
  }

  expect_failures = [check.no_burstable_with_ha]
}

run "reject_low_pitr_production_shaped" {
  command = plan

  variables {
    environment                   = "staging"
    server_count                  = 2
    database_count                = 10
    role_count                    = 10
    postgis_on_core               = false
    postgis_on_parking            = true
    public_network_access_enabled = false
    core_backup_retention_days    = 7
    parking_backup_retention_days = 7
    core_ha_mode                  = "ZoneRedundant"
    parking_ha_mode               = "ZoneRedundant"
    core_sku_name                 = "GP_Standard_D2s_v3"
    parking_sku_name              = "GP_Standard_D2s_v3"
    production_shaped             = true
    apply_authorized              = false
    tls_client_mode               = "verify-full"
    provider_topology             = "azure-postgresql-flexible-server-vnet-private-dns"
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
  }

  expect_failures = [check.production_shaped_pitr]
}

run "rejects_privatelink_dns" {
  command = plan

  variables {
    environment                   = "sandbox"
    server_count                  = 2
    database_count                = 10
    role_count                    = 10
    postgis_on_core               = false
    postgis_on_parking            = true
    public_network_access_enabled = false
    core_backup_retention_days    = 7
    parking_backup_retention_days = 7
    core_ha_mode                  = "Disabled"
    parking_ha_mode               = "Disabled"
    core_sku_name                 = "GP_Standard_D2s_v3"
    parking_sku_name              = "GP_Standard_D2s_v3"
    production_shaped             = false
    apply_authorized              = false
    tls_client_mode               = "verify-full"
    provider_topology             = "azure-postgresql-flexible-server-vnet-private-dns"
    private_dns_zone_name         = "privatelink.postgres.database.azure.com"
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
  }

  expect_failures = [check.dns_vnet_integration_suffix]
}

run "rejects_staging_apply_authorized" {
  command = plan

  variables {
    environment                   = "staging"
    server_count                  = 2
    database_count                = 10
    role_count                    = 10
    postgis_on_core               = false
    postgis_on_parking            = true
    public_network_access_enabled = false
    core_backup_retention_days    = 7
    parking_backup_retention_days = 7
    core_ha_mode                  = "Disabled"
    parking_ha_mode               = "Disabled"
    core_sku_name                 = "GP_Standard_D2s_v3"
    parking_sku_name              = "GP_Standard_D2s_v3"
    production_shaped             = false
    apply_authorized              = true
    authorization_reference       = "PP-01B-MODE-B-20260804-01"
    sandbox_cleanup_deadline      = "2026-08-05T00:00:00Z"
    tls_client_mode               = "verify-full"
    provider_topology             = "azure-postgresql-flexible-server-vnet-private-dns"
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
  }

  expect_failures = [check.staging_apply_forbidden]
}
