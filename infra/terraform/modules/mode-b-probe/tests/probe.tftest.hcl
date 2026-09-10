# Mode B probe offline tests (PP-01B-IAC-02)

mock_provider "azurerm" {}

run "probe_negative_proofs_when_not_created" {
  command = plan

  variables {
    create                       = false
    environment                  = "sandbox"
    name                         = "vm-pp01b-mb-20260804-7nr2-probe"
    location                     = "francecentral"
    resource_group_name          = "rg-pp01b-mb-20260804-7nr2"
    probe_subnet_id              = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-x/providers/Microsoft.Network/virtualNetworks/vnet-x/subnets/snet-probe"
    delegated_postgres_subnet_id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-x/providers/Microsoft.Network/virtualNetworks/vnet-x/subnets/snet-pg"
  }

  assert {
    condition     = output.public_ip_count == 0
    error_message = "public IP count must be 0"
  }

  assert {
    condition     = output.inbound_ssh_rule_count == 0
    error_message = "SSH rule count must be 0"
  }

  assert {
    condition     = output.uses_probe_subnet == true
    error_message = "probe must declare probe-subnet usage"
  }

  assert {
    condition     = output.created == false
    error_message = "default create must be false"
  }
}

run "rejects_non_sandbox_environment" {
  command = plan

  variables {
    create              = false
    environment         = "production"
    name                = "vm-pp01b-mb-20260804-7nr2-probe"
    location            = "francecentral"
    resource_group_name = "rg-pp01b-mb-20260804-7nr2"
    probe_subnet_id     = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-x/providers/Microsoft.Network/virtualNetworks/vnet-x/subnets/snet-probe"
  }

  expect_failures = [var.environment]
}
