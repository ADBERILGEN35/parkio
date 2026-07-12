# Azure Cost Model

## Method and limits

Prices below were queried from the unauthenticated Microsoft Azure Retail Prices API on 2026-07-12 for `westeurope`, USD PAYG Linux consumption. They are retail meters, exclude tax, negotiated discounts, currency conversion, quota effects, and free-account eligibility. Portal/Calculator verification immediately before creation is mandatory.

Fixed formulas use 720 hours for 30 days:

```text
VM = hourly_rate * 24 * days
Static IPv4 = 0.005 * 24 * days
Disk monthly = E6 64 GiB 4.80 + E10 128 GiB 9.60
Disk transactions = transaction_count / 10,000 * 0.0026
Snapshot LRS = used_snapshot_GiB_month * 0.145
Hot Blob LRS = stored_GB_month * 0.0196 plus operations
```

The Retail API returned multiple bandwidth tiers. Egress is therefore not assigned an invented flat amount; validate the applicable subscription tier and use `billable_GB * applicable_meter`. The observed Internet routing meter includes USD 0.08/GB tiers. Taxes are excluded.

## Meter evidence

| Meter | Retail rate |
|---|---:|
| Linux `Standard_D4as_v5` | USD 0.208/hour |
| Linux `Standard_D8as_v5` | USD 0.416/hour |
| Linux `Standard_B4ms` | USD 0.192/hour |
| Linux `Standard_B8ms` | USD 0.384/hour |
| Linux `Standard_B8ls_v2` | USD 0.340/hour |
| Linux `Standard_D8ls_v5` 8 vCPU / 16 GiB | USD 0.388/hour |
| Linux `Standard_D8als_v6` 8 vCPU / 16 GiB | USD 0.389/hour |
| Standard IPv4 static public IP | USD 0.005/hour |
| Standard SSD LRS E6, 64 GiB | USD 4.80/month |
| Standard SSD LRS E10, 128 GiB | USD 9.60/month |
| Standard SSD LRS operations | USD 0.0026/10K |
| Standard SSD LRS snapshots | USD 0.145/used GB-month |

## Profiles

| Profile | Fixed daily | Fixed 30-day | 7 / 14 / 21 / 30-day usage | Buffer from USD 200 before variable usage | Risk |
|---|---:|---:|---:|---:|---|
| Minimum experiment: B4ms + E6/E10 + IPv4 | USD 5.208 | USD 156.24 | 36.46 / 72.91 / 109.37 / 156.24 | USD 43.76 | CPU-credit and 16-GiB risk |
| Recommended: D4as_v5 + E6/E10 + IPv4 | **USD 5.592** | **USD 167.76** | 39.14 / 78.29 / 117.43 / 167.76 | **USD 32.24** | tight memory, 4-vCPU startup risk |
| Safer: D8as_v5 + E6/E10 + IPv4 | USD 10.584 | USD 317.52 | 74.09 / 148.18 / 222.26 / 317.52 | negative USD 117.52 | credit exhausts near day 18.9 |
| 8-vCPU/16-GiB D8ls_v5 + E6/E10 + IPv4 | USD 9.912 | USD 297.36 | 69.38 / 138.77 / 208.15 / 297.36 | negative USD 97.36 | still unaffordable |

Fixed totals do **not** include egress, disk operations, snapshots, optional Blob storage, Azure Backup, Defender, Log Analytics, tax, or third-party Resend/Expo/MapTiler costs. For the recommended profile, operational shutdown criteria should preserve at least USD 20 of credit; this leaves only USD 12.24 for all variable Azure meters if it runs the full 30 days.

## Managed-services floor

Current West Europe retail meters include:

- PostgreSQL Flexible Server burstable B2ms: USD 0.1592/hour = USD 114.624/30d, plus USD 0.1369/GB-month storage.
- Azure Redis Basic C0: USD 0.022/hour = USD 15.84/30d.
- Event Hubs Standard throughput unit: USD 0.03/hour = USD 21.60/30d plus ingress events.
- Container Apps active: USD 0.000034/vCPU-second + USD 0.000004/GiB-second + request charges.

A deliberately small Container Apps assumption of ten always-on services at only 0.5 vCPU and 1 GiB each costs:

```text
CPU:    5 vCPU * 2,592,000 sec * 0.000034 = USD 440.64
Memory: 10 GiB * 2,592,000 sec * 0.000004 = USD 103.68
Apps subtotal = USD 544.32 before requests, data, registry, ingress, or logs
```

Adding B2ms PostgreSQL, 64 GiB database storage (USD 8.76), C0 Redis, one Event Hubs TU, and 50 GB Hot Blob (USD 0.98) yields a transparent **USD 705+ 30-day floor** before requests, backup, monitoring, and egress. It is not a recommended configuration and does not prove compatibility; it demonstrates why the managed option is outside this credit.

## Credit duration

| Configuration | Approximate fixed-cost exhaustion |
|---|---:|
| B4ms experiment | 38.4 days; 30-day credit expiry occurs first |
| D4as_v5 recommended | 35.8 days; variable usage can pull this inside 30 days |
| D8ls_v5 8/16 | 20.2 days |
| D8as_v5 8/32 | 18.9 days |
| Managed floor | under 8.6 days |

## Cost traps and controls

- **Stopped is not necessarily deallocated.** Use `az vm deallocate`; verify `PowerState/deallocated`. Compute stops, disks/snapshots and some network resources remain billable.
- A static public IPv4 remains billable until deleted.
- Deleting only the VM can leave disks, NIC, IP, snapshots, vaults, workspaces, and storage accounts.
- NAT Gateway, Application Gateway, managed Kafka, paid Defender plans, Log Analytics ingestion, managed database minimum tiers, Premium SSD, and Azure Backup protected-instance fees are excluded and should not be enabled.
- Azure Backup adds a USD 10/month protected-instance meter for an Azure VM before storage; use repository-native encrypted logical backups copied off-VM for this temporary beta.
- Snapshots are incremental but billed by stored used GiB. Delete obsolete snapshots.
- Docker image layers and ten database volumes can grow the data disk even at low user traffic. Review `df`, `docker system df`, MinIO bytes, Kafka logs, and DB sizes daily.
- If the free credit expires or is exhausted, the free subscription can be disabled and VMs deallocated; do not rely on grace time to export data.

## Portal verification

Before creating the VM, open **Azure Pricing Calculator -> Virtual Machines** and select West Europe, Linux, PAYG, `D4as v5`, 720 hours, Standard SSD E6/E10, one Standard IPv4. Compare the result with the API query and record a screenshot/date in the deployment evidence.

## Microsoft references

- [Azure Retail Prices REST API](https://learn.microsoft.com/en-us/rest/api/cost-management/retail-prices/azure-retail-prices)
- [Azure B-series CPU credit model](https://learn.microsoft.com/en-us/azure/virtual-machines/sizes/b-series-cpu-credit-model)
- [Create and manage Cost Management budgets](https://learn.microsoft.com/en-us/azure/cost-management-billing/costs/tutorial-acm-create-budgets)
- [Avoid charges with an Azure free account](https://learn.microsoft.com/en-us/azure/cost-management-billing/manage/avoid-charges-free-account)
- [Azure subscription states](https://learn.microsoft.com/en-us/azure/cost-management-billing/manage/subscription-states)
