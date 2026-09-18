# Municipal open-data egress (OPS-EGRESS-MUNI-01)

Provider-neutral hosted-beta reachability policy and 2026-08-13 diagnosis.
This is an **ops/network** record. It does not change ranking, parsing, or
provider adapters.

## Decision (2026-08-13)

**BLOCKED — REMOTE SOURCE REQUIRES MUNICIPAL ALLOWLIST** for Kayseri.

Azure hosted-beta networking is healthy. Official Kayseri GeoJSON is reachable
from a Turkish residential client and returns HTTP 200. The same destination
does not accept TCP/443 from Azure France Central (`20.199.17.76`, AS8075).
Konya remains a **separate** Cloudflare 403 challenge and stays deferred.

Kayseri and Konya schedulers must stay **OFF** until the official publisher
allows this egress identity (or provides an approved API/allowlist path).

## Future provider checklist (blocking)

Before implementing or accepting a municipal provider, probe the **exact
official URL** from all three vantage points:

| # | Vantage | How |
|---|---------|-----|
| 1 | Developer/local | `scripts/probe-municipal-egress.sh <url>` |
| 2 | GitHub Actions | same script in a workflow job, or a one-shot CI step |
| 3 | Hosted-beta host **and** `parking-service` container | Azure run-command / SSH |

Record:

```
HOSTED_BETA_EGRESS_REACHABLE = YES | NO
```

Acceptance requires **YES** from hosted-beta host and container, with TLS
verified and no unofficial proxy/mirror. A local-only 200 is not sufficient.

Do **not**:

- raise connect timeouts to hide unreachable hosts
- rotate User-Agent / headers to evade bot protection
- disable TLS verification
- use residential/proxy/VPN relays
- hardcode unofficial mirrors

## Hosted-beta topology (unchanged)

| Item | Value |
|------|-------|
| Subscription | Azure subscription 1 `2b3abb5c-8a52-4c03-91c7-f53fd440a7e9` |
| Resource group | `rg-parkio-hosted-beta` (RG location `westeurope`) |
| VM | `vm-parkio-hosted-beta` `Standard_D4as_v7` **francecentral** zone 1 |
| Public IP | `vm-parkio-hosted-beta-ip` **Static Standard IPv4** `20.199.17.76` |
| NIC | `vm-parkio-hosted-beta116_z1` private `10.0.0.4` |
| VNet / subnet | `vm-parkio-hosted-beta-vnet` `10.0.0.0/16` / `default` `10.0.0.0/24` |
| NSG | `vm-parkio-hosted-beta-nsg` |
| Route table | none (default `0.0.0.0/0` → Internet) |
| NAT Gateway | **none** |
| Azure Firewall | **none** |
| DNS | Azure host `127.0.0.53` (systemd-resolved); container `127.0.0.11` → host |
| Docker | `parkio-backend` bridge `172.18.0.0/16`; parking `172.18.0.10` |

Outbound 443 is permitted by default NSG `AllowInternetOutBound`. Custom NSG
rules are inbound only (SSH from operator IP, public 80/443).

The VM already has a **stable public egress IP** via the instance-level public
IP. A NAT Gateway would not change ASN (still Microsoft AS8075) and is not
justified for this single-VM topology.

## Public egress identity

| Vantage | Observed IPv4 | IPv6 |
|---------|---------------|------|
| Developer machine | `95.70.149.10` (TR residential) | present |
| Hosted-beta host | `20.199.17.76` | none (IPv4-only NIC/PIP) |
| `parking-service` container | `20.199.17.76` | none |

Host and container share the same SNAT identity.

## Kayseri vs Konya (do not conflate)

| | Kayseri | Konya |
|---|---------|-------|
| Official host | `acikveri.kayseri.bel.tr` | `acikveri.konya.bel.tr` |
| Resolved | `212.175.206.120` A only (no AAAA) | Cloudflare IPv4+IPv6 |
| ASN | AS9121 Turk Telekom (`*.static.ttnet.com.tr`) | Cloudflare |
| Local | TCP+TLS+HTTP **200** (~35 ms) | HTTP **200** (CF allows residential) |
| Hosted-beta | TCP **timeout** curl 28 / `connect_timeout` | TCP OK, HTTP **403** `cf-mitigated: challenge` |
| TLS from Azure | never reached | Cloudflare cert (handshake succeeds) |
| Classification | REMOTE FIREWALL/ACL (Azure ASN/datacenter likely) | Cloudflare bot challenge |
| Scheduler | OFF | OFF |

Kayseri 5s connect timeout is correct: the host is unreachable from Azure, not
slow. Do not raise it.

## Runtime flags (hosted-beta)

Until publishers allowlist or provide an official API path:

```
PARKIO_MUNICIPAL_KAYSERI_ENABLED=false
PARKIO_MUNICIPAL_KAYSERI_SCHEDULER_ENABLED=false
PARKIO_MUNICIPAL_KONYA_ENABLED=false
PARKIO_MUNICIPAL_KONYA_SCHEDULER_ENABLED=false
```

Do not leave daily failing jobs enabled. İZUM / İSPARK / ANPARK / OSM flags
must not be changed by this package.

## Prepared Kayseri allowlist request (DO NOT SEND automatically)

Contact observed on the official portal: `acikveri@kayseri.bel.tr`.

Suggested content for a human operator to send:

- Parkio is a parking-discovery product that would display Kayseri Büyükşehir
  Belediyesi open parking inventory with CC BY 4.0 attribution.
- Dataset: https://acikveri.kayseri.bel.tr/veri-seti/kayseri-otoparklar/35
- Resource: `https://acikveri.kayseri.bel.tr/uploads/data/2024/10/7/otoparklar-456371.geojson`
- Read-only HTTPS GET, approximately once per day (static inventory).
- Hosted-beta egress IPv4: **`20.199.17.76/32`** (Azure France Central, AS8075).
- Request: IP allowlist and/or official API access guidance.
- Parkio is not affiliated with or endorsed by Kayseri Metropolitan Municipality.

## Resume procedure (Kayseri only)

1. Operator sends allowlist request (or municipality publishes an approved API).
2. Re-run `scripts/probe-municipal-egress.sh` from **host and container**.
3. Require HTTP 200, valid UTF-8 GeoJSON, current feature count, verified TLS.
4. Then set Kayseri enabled+scheduler true only. Leave Konya OFF.
5. First sync must be SUCCESS, `occupancy_inserted=0`, then nearby/detail/web.

Konya is **not** unblocked by a Kayseri allowlist. Resume PROVIDER-KONYA-01R
only if the official CKAN host returns normal (non-challenge) JSON from
hosted-beta without evasion.

## Rollback

Re-set the four flags above to `false` and recreate `parking-service` with
`PARKIO_IMAGE_TAG` set. No Azure network resources were created by this package.
