# Parkio Azure Hosted-Beta Plan

**Audit date:** 2026-07-12

**Scope:** planning and repository verification only; no Azure resource was created and no deployment was attempted.

**Region used for cost comparison:** West Europe (`westeurope`). Recheck availability and price in the subscription before creation.

## Decision in one page

Parkio can use the temporary USD 200 credit for a **closed, temporary, single-VM validation**, but not for the repository's fully sized all-in-one stack for 30 continuous days.

| Question | Decision |
|---|---|
| Recommended architecture | One Azure Linux VM, one VNet/subnet/NIC, one Standard static IPv4, NSG, Standard SSD OS/data disks; Caddy is the only public container |
| Recommended VM for the credit | `Standard_D4as_v5`, 4 vCPU / 16 GiB, **conditional** on reduced observability and live memory/startup validation |
| Repository-sized all-in-one VM | 8 vCPU / at least 24 GiB; nearest practical Azure choice is 32 GiB and exceeds USD 200 for 30 days |
| Minimum experiment | `Standard_B4ms`, 4 vCPU / 16 GiB, scheduled/non-continuous only; not recommended for a 24/7 beta |
| 8 GiB | NO-GO: measured reduced stack used about 7.7 GiB idle and 10 GiB under read load |
| Public entry points | TCP 80/443, UDP 443 optional for HTTP/3; SSH TCP 22 from one operator CIDR only |
| Public DNS | `app.parkio.dev`, `api.parkio.dev`, `media.parkio.dev`; apex `parkio.dev` stays on Hostinger |
| Environment class | Temporary validation, not HA and not migration-ready production |

The `D4as_v5` is below the repository's documented 8-vCPU minimum. It is selected only because the credit constraint makes the 8-vCPU choices unaffordable and prior measured read load used roughly 2-3 CPU cores. That benchmark omitted part of observability and did not test writes, uploads, long soaks, or simultaneous cold starts. The deployment therefore stays **CONDITIONAL GO**, not certified.

## Documents

- [AZ2 blocker resolution](AZ2-BLOCKER-RESOLUTION-REPORT.md)
- [Deterministic deployment profile](AZURE-DEPLOYMENT-PROFILE.md)
- [Runtime service matrix](AZURE-RUNTIME-SERVICE-MATRIX.md)
- [Readiness and inventory](AZURE-HOSTED-BETA-READINESS.md)
- [Architecture options](AZURE-ARCHITECTURE-OPTIONS.md)
- [Cost model](AZURE-COST-MODEL.md)
- [Operator deployment runbook](AZURE-DEPLOYMENT-RUNBOOK.md)
- [Security checklist](AZURE-SECURITY-CHECKLIST.md)
- [DNS and TLS](AZURE-DNS-AND-TLS-PLAN.md)
- [Backup and exit](AZURE-BACKUP-AND-EXIT-PLAN.md)
- [30-day credit plan](AZURE-30-DAY-CREDIT-PLAN.md)
- [GO/NO-GO matrix](AZURE-GO-NO-GO.md)

## Evidence hierarchy

1. Current Compose, scripts, Dockerfiles, application configuration, and tests.
2. Measured repository benchmark in `benchmarks/reports/p221/REPORT.md`.
3. Existing operational documents, checked against current configuration.
4. Microsoft Azure Retail Prices API results queried on 2026-07-12.

AZ2 resolved the waitlist timeout, canonical API hostname, deterministic reduced profile, and ten-database backup coverage. Documentation claims are not runtime proof: Azure account, SKU quota, Docker Compose rendering, image manifests, deployment, TLS, backup, restore, and smoke still require the target VM.
