# Database + application role provisioning (M-DB-ROLES)
#
# Azure server admin is separate from application roles.
# Live resources are gated by enable_live_bootstrap (default false) so offline
# validate/init never requires a reachable PostgreSQL server.
# Migrator/runtime remain temporarily shared per PP-01B-R0 (no split).

terraform {
  required_version = ">= 1.5.0, < 2.0.0"
  required_providers {
    postgresql = {
      source  = "cyrilgdn/postgresql"
      version = "~> 1.25"
    }
  }
}

variable "enable_live_bootstrap" {
  type        = bool
  description = "When false (default), only manifest/policy locals are evaluated offline."
  default     = false
}

variable "cluster" {
  type        = string
  description = "core or parking — which server this module targets."
  validation {
    condition     = contains(["core", "parking"], var.cluster)
    error_message = "cluster must be core or parking."
  }
}

variable "service_role_passwords" {
  type        = map(string)
  sensitive   = true
  description = "Map of role name → password. Required only when enable_live_bootstrap=true."
  default     = {}
}

# Canonical ADR inventory (subset filtered by cluster).
locals {
  all_services = {
    "auth-service" = {
      database = "parkio_auth"
      role     = "parkio_auth"
      cluster  = "core"
    }
    "gateway-service" = {
      database = "parkio_gateway"
      role     = "parkio_gateway"
      cluster  = "core"
    }
    "user-service" = {
      database = "parkio_user"
      role     = "parkio_user"
      cluster  = "core"
    }
    "media-service" = {
      database = "parkio_media"
      role     = "parkio_media"
      cluster  = "core"
    }
    "gamification-service" = {
      database = "parkio_gamification"
      role     = "parkio_gamification"
      cluster  = "core"
    }
    "notification-service" = {
      database = "parkio_notification"
      role     = "parkio_notification"
      cluster  = "core"
    }
    "moderation-service" = {
      database = "parkio_moderation"
      role     = "parkio_moderation"
      cluster  = "core"
    }
    "analytics-service" = {
      database = "parkio_analytics"
      role     = "parkio_analytics"
      cluster  = "core"
    }
    "ai-validation-service" = {
      database = "parkio_aivalidation"
      role     = "parkio_aivalidation"
      cluster  = "core"
    }
    "parking-service" = {
      database = "parkio_parking"
      role     = "parkio_parking"
      cluster  = "parking"
    }
  }

  for_cluster = {
    for svc, cfg in local.all_services : svc => cfg
    if cfg.cluster == var.cluster
  }

  databases = toset([for _, cfg in local.for_cluster : cfg.database])
  roles     = toset([for _, cfg in local.for_cluster : cfg.role])
}

check "unique_dbs_and_roles_in_cluster" {
  assert {
    condition = (
      length(local.databases) == length(local.for_cluster) &&
      length(local.roles) == length(local.for_cluster)
    )
    error_message = "Each service in a cluster must have a unique database and unique role."
  }
}

check "passwords_when_live" {
  assert {
    condition = (
      !var.enable_live_bootstrap ||
      length(setsubtract(local.roles, toset(keys(var.service_role_passwords)))) == 0
    )
    error_message = "service_role_passwords must include every role when enable_live_bootstrap=true."
  }
}

resource "postgresql_database" "app" {
  for_each = var.enable_live_bootstrap ? local.databases : toset([])
  name     = each.value
}

resource "postgresql_role" "app" {
  for_each = var.enable_live_bootstrap ? local.roles : toset([])
  name     = each.value
  login    = true
  password = var.service_role_passwords[each.value]
}

resource "postgresql_grant" "connect" {
  for_each    = var.enable_live_bootstrap ? local.for_cluster : {}
  database    = each.value.database
  role        = each.value.role
  object_type = "database"
  privileges  = ["CONNECT"]

  depends_on = [
    postgresql_database.app,
    postgresql_role.app,
  ]
}

# Ownership: application role owns its database (shared migrator/runtime temporarily).
resource "postgresql_grant" "owner_schema" {
  for_each    = var.enable_live_bootstrap ? local.for_cluster : {}
  database    = each.value.database
  role        = each.value.role
  schema      = "public"
  object_type = "schema"
  privileges  = ["USAGE", "CREATE"]

  depends_on = [
    postgresql_database.app,
    postgresql_role.app,
  ]
}

output "cluster" {
  value = var.cluster
}

output "service_map" {
  value       = local.for_cluster
  description = "Service → database/role/cluster for this server (non-secret)."
}

output "database_names" {
  value = sort(tolist(local.databases))
}

output "role_names" {
  value = sort(tolist(local.roles))
}

output "live_bootstrap_enabled" {
  value = var.enable_live_bootstrap
}

output "cross_database_grants" {
  value       = []
  description = "Contract: zero cross-database grants."
}

output "flyway_scope" {
  value       = "schema_migrations_only"
  description = "Flyway owns schema; Terraform owns DB/role existence."
}

output "runtime_role_scope" {
  value       = "shared_with_migrator_temporarily"
  description = "PP-01B-R0: migrator/runtime not split yet."
}
