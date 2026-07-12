# Azure 30-Day Credit Plan

## Budget guardrails

| Threshold | Action |
|---:|---|
| USD 50 actual/forecast | validate meter mix; remove accidental diagnostics/snapshots |
| USD 100 | midpoint architecture review; confirm exit artifacts |
| USD 150 | no new paid resources; decide continue/exit by day 21 |
| USD 175 | export fresh backup and schedule deallocation within 24h |
| USD 190 | emergency backup, DNS maintenance, immediate deallocate/delete |

Target a USD 180 forecast ceiling, not USD 200. Check **Cost Management -> Cost analysis** daily, grouped by resource and meter. Cost data can lag; also inspect the actual resource list.

## Operating calendar

| Day | Work and evidence | Stop condition |
|---|---|---|
| 0 | verify subscription/expiry/quota; query prices; create USD 180 budget/alerts; RG/network/NSG/IP/D4as_v5/disks; harden SSH/OS; install Docker | price/quota/credit mismatch |
| 1 | Azure profile env/preflight/render/dry runs; deploy deterministic reduced set; DNS/TLS; health/auth/waitlist/upload smoke; first backup | any secret, profile, public-port, TLS, OOM, or backup failure |
| 2 | disposable parking restore; inspect memory/CPU/disk/restarts; verify Prometheus targets and off-VM backup | less than 2 GiB normal memory headroom or swap/OOM |
| 3 | cold restart timing and startup memory capture in maintenance window | restart loop, startup OOM, unacceptable downtime |
| 4-7 | named internal testers only; daily cost/health; one write/upload flow; Kafka lag/outbox/DLT check | error/lag/data-loss trend |
| 8-14 | controlled closed beta; nightly backup/off-host copy; patch day; no architecture expansion | forecast above USD 150 by day 14 |
| 15-21 | continue evidence collection; execute live rollback after second release; weekly full restore drill | no verified rollback/restore by day 21 |
| 22 | founder decision: paid continuation, migrate, or stop | default is stop without approved recurring budget |
| 23-25 | freeze additions; provision destination only under separate budget; lower DNS TTL; inventory secrets/data | destination not proven -> export and stop Azure |
| 26 | final full backup and restore validation; export manifests/dashboards/cost evidence | failed export blocks deletion but triggers deallocation after safe staging |
| 27 | migration smoke or maintenance announcement; final credential inventory | any unknown data owner |
| 28 | switch/remove DNS; rotate provider/runtime secrets | traffic still reaches Azure unexpectedly |
| 29 | deallocate VM; verify power state and residual resources | never leave merely `stopped` |
| 30 | delete resource group/budget after evidence; subscription-wide residual search | any disk/IP/vault/workspace remains |

## Daily five-minute review

```bash
az resource list -g "$RESOURCE_GROUP" -o table
az vm show -d -g "$RESOURCE_GROUP" -n "$VM_NAME" --query powerState -o tsv
ssh <SSH_USER>@<PUBLIC_IP> 'free -h; df -h / /var/lib/docker; docker ps --format "table {{.Names}}\t{{.Status}}"; docker stats --no-stream'
```

Review Portal cost because `az costmanagement query` may not be enabled for a free subscription. Also inspect:

- kernel OOM: `sudo journalctl -k --since yesterday | grep -Ei 'oom|out of memory|killed process'`
- restarts: `docker inspect -f '{{.Name}} {{.RestartCount}}' $(docker ps -q)`
- Prometheus target/host disk/memory/Kafka lag alerts through SSH tunnel
- backup manifest age and local off-host copy
- `docker system df`, PostgreSQL sizes, Kafka volume, MinIO object bytes

## Shutdown and migration triggers

Immediate shutdown: credit forecast USD 190, unknown billable service, backup cannot be exported, public internal port, compromised credential, repeated OOM, disk above 90%, or subscription expiry within 48h.

Migration trigger: beta value is proven, recurring budget is approved, 7 days of stable measurements exist, write/upload/restore/rollback pass, and the destination cost/operations owner is named. Without all conditions, export and delete rather than allowing accidental pay-as-you-go continuation.
