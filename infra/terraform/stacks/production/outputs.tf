output "environment" {
  value = var.environment
}

output "core_fqdn" {
  value = module.core.fqdn
}

output "parking_fqdn" {
  value = module.parking.fqdn
}

output "core_resource_id" {
  value = module.core.id
}

output "parking_resource_id" {
  value = module.parking.id
}

output "private_dns_zone_id" {
  value = module.network.private_dns_zone_id
}

output "private_dns_zone_name" {
  value = module.network.private_dns_zone_name
}

output "service_database_role_cluster_map" {
  value = local.service_manifest
}

output "postgres_version" {
  value = var.postgres_version
}

output "ha_mode_by_server" {
  value = {
    core    = module.core.ha_mode
    parking = module.parking.ha_mode
  }
}

output "backup_retention_by_server" {
  value = {
    core    = module.core.backup_retention_days
    parking = module.parking.backup_retention_days
  }
}

output "public_network_access_enabled" {
  value = local.public_network_access_enabled
}

output "postgis_enabled" {
  value = {
    core     = false
    parking  = true
    database = "parkio_parking"
  }
}

output "tls_client_mode_handoff" {
  value = "verify-full"
}

output "production_enablement" {
  value = var.production_enablement
}

output "apply_authorized" {
  value = false
}

output "create_azure_resources" {
  value = false
}

output "definition_only" {
  value = true
}
