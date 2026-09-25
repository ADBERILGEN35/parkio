# Mode B cleanup ownership manifest (PP-01B-IAC-02)

Destroy order (Terraform reverse-dependency / typical apply reverse):

| Order | Terraform address | Azure type | Module | Post-destroy verify |
|------:|-------------------|------------|--------|---------------------|
| 1 | `module.probe.azurerm_linux_virtual_machine.probe` | Microsoft.Compute/virtualMachines | mode-b-probe | VM absent |
| 2 | `module.probe.azurerm_network_interface.probe` | Microsoft.Network/networkInterfaces | mode-b-probe | NIC absent |
| 3 | `module.core.azurerm_postgresql_flexible_server.this` | Microsoft.DBforPostgreSQL/flexibleServers | postgresql-flexible-server | FS count −1 |
| 4 | `module.parking.azurerm_postgresql_flexible_server.this` | Microsoft.DBforPostgreSQL/flexibleServers | postgresql-flexible-server | FS count −1 |
| 5 | `module.network.azurerm_private_dns_zone_virtual_network_link.postgres` | Microsoft.Network/privateDnsZones/virtualNetworkLinks | network-dns | link absent |
| 6 | `module.network.azurerm_subnet.probe` | Microsoft.Network/virtualNetworks/subnets | network-dns | subnet absent |
| 7 | `module.network.azurerm_subnet.postgres` | Microsoft.Network/virtualNetworks/subnets | network-dns | subnet absent |
| 8 | `module.network.azurerm_private_dns_zone.postgres` | Microsoft.Network/privateDnsZones | network-dns | zone absent |
| 9 | `module.network.azurerm_virtual_network.this` | Microsoft.Network/virtualNetworks | network-dns | VNet absent |
| 10 | `module.disposable_rg.azurerm_resource_group.mode_b` | Microsoft.Resources/resourceGroups | disposable-rg | RG deleted (final) |

External (not destroyed by Terraform):

- `Microsoft.DBforPostgreSQL` provider registration — **retain**

Bootstrap (when `enable_live_bootstrap=true`):

- `postgresql_database` / `postgresql_role` / `postgresql_extension` resources are destroyed with Terraform destroy of the postgresql provider resources **before** server destroy; if servers are destroyed first, bootstrap objects are gone with the servers. Prefer: disable bootstrap → destroy servers → destroy network → delete RG.

Relock after destroy:

- `apply_authorized=false`
- `create_disposable_rg=false`
- `create_network=false`
- `create_probe=false`
- `create_azure_resources=false`
- `enable_live_bootstrap=false`

Forbidden in cleanup graph: any `hosted-beta` resource ID.
