output "environment" {
  value = var.environment
}

output "resource_group_name" {
  value = module.disposable_rg.name
}

output "resource_group_id" {
  value = module.disposable_rg.id
}

output "core_fqdn" {
  value       = module.core.fqdn
  description = "Core Flexible Server FQDN (null until created)."
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

output "probe_subnet_id" {
  value = module.network.probe_subnet_id
}

output "probe_vm_id" {
  value = module.probe.vm_id
}

output "probe_public_ip_count" {
  value = module.probe.public_ip_count
}

output "probe_inbound_ssh_rule_count" {
  value = module.probe.inbound_ssh_rule_count
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

output "sandbox_cleanup_deadline" {
  value = local.sandbox_cleanup_deadline
}

output "diagnostics_placeholder_enabled" {
  value = local.diagnostics_placeholder
}

output "postgis_enabled" {
  value = {
    core     = false
    parking  = module.postgis.postgis_enabled
    database = module.postgis.target_database
  }
}

output "tls_client_mode_handoff" {
  value       = "verify-full"
  description = "PP-01C target sslmode; Terraform does not bind JDBC."
}

output "policy_guards" {
  value = module.policy.guards
}

output "apply_authorized" {
  value = var.apply_authorized
}

output "create_azure_resources" {
  value = var.create_azure_resources
}

output "create_network" {
  value = var.create_network
}

output "create_disposable_rg" {
  value = var.create_disposable_rg
}

output "create_probe" {
  value = var.create_probe
}

output "mode_b_status" {
  value = module.postgis.mode_b_dependency
}

output "pp01c_jdbc_owned_by_terraform" {
  value = false
}

output "cleanup_inventory" {
  value = concat(
    module.disposable_rg.cleanup_inventory,
    module.network.cleanup_inventory,
    module.probe.cleanup_inventory,
    [
      "module.core.azurerm_postgresql_flexible_server.this",
      "module.parking.azurerm_postgresql_flexible_server.this",
    ]
  )
}

output "relock_checklist" {
  value = {
    apply_authorized               = false
    create_disposable_rg           = false
    create_network                 = false
    create_probe                   = false
    create_azure_resources         = false
    enable_live_bootstrap          = false
    provider_postgresql_registered = "retain Registered; do not unregister"
  }
}
