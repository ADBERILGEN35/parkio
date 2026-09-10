# Network-DNS offline tests (PP-01B-IAC-02)
# Uses mock_provider so no real Azure credentials are required.

mock_provider "azurerm" {}

run "accepts_vnet_integration_dns_name" {
  command = plan

  variables {
    naming_prefix                = "pp01b-mb-20260804-7nr2"
    location                     = "francecentral"
    resource_group_name          = "rg-pp01b-mb-20260804-7nr2"
    create_network               = false
    existing_vnet_id             = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-x/providers/Microsoft.Network/virtualNetworks/vnet-x"
    existing_delegated_subnet_id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-x/providers/Microsoft.Network/virtualNetworks/vnet-x/subnets/snet-pg"
    existing_probe_subnet_id     = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-x/providers/Microsoft.Network/virtualNetworks/vnet-x/subnets/snet-probe"
    existing_private_dns_zone_id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-x/providers/Microsoft.Network/privateDnsZones/pp01b-mb-20260804-7nr2.private.postgres.database.azure.com"
    private_dns_zone_name        = "pp01b-mb-20260804-7nr2.private.postgres.database.azure.com"
  }

  assert {
    condition     = output.private_dns_zone_name == "pp01b-mb-20260804-7nr2.private.postgres.database.azure.com"
    error_message = "expected VNet-integration DNS zone name"
  }

  assert {
    condition     = output.public_ingress_exposed == false
    error_message = "public ingress must remain false"
  }
}

run "rejects_privatelink_dns_variable" {
  command = plan

  variables {
    naming_prefix                = "pp01b-mb-20260804-7nr2"
    location                     = "francecentral"
    resource_group_name          = "rg-pp01b-mb-20260804-7nr2"
    create_network               = false
    existing_vnet_id             = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-x/providers/Microsoft.Network/virtualNetworks/vnet-x"
    existing_delegated_subnet_id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-x/providers/Microsoft.Network/virtualNetworks/vnet-x/subnets/snet-pg"
    existing_probe_subnet_id     = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-x/providers/Microsoft.Network/virtualNetworks/vnet-x/subnets/snet-probe"
    existing_private_dns_zone_id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-x/providers/Microsoft.Network/privateDnsZones/privatelink.postgres.database.azure.com"
    private_dns_zone_name        = "privatelink.postgres.database.azure.com"
  }

  expect_failures = [var.private_dns_zone_name]
}
