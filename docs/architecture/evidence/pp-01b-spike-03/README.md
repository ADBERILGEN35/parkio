# PP-01B-SPIKE-03 evidence index

Evidence snapshot: **2026-08-04**.

## Documentation inventory

| Item | Location | Class |
|------|----------|-------|
| Mode A report + contract | [`../pp-01b-spike-03-private-network-dns-tls.md`](../pp-01b-spike-03-private-network-dns-tls.md) | Deliverable |
| Production private-only guard | `platform/.../ProductionPrivateConnectivityGuard.java` | REPOSITORY FACT |
| TLS IT | `PostgresTlsConnectivityIT` | Mode A runtime |
| DNS contract tests | `DnsEndpointChangeContractTest` | Local contract |
| Scripts | `scripts/pp-01b-spike-03-mode-a.ps1`, `.sh` | Harness |
| Machine evidence (gitignored) | `deploy-artifacts/pp-01b-spike-03/` | Runtime |

## Mode A local runtime

| Item | Value |
|------|-------|
| Decision | **PASS WITH NON-BLOCKING NOTES** |
| Image digest | `postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777` |
| JDBC | `org.postgresql:postgresql:42.7.11` |
| TLS policy | `verify-full` |
| Required IT skipped | **0** |
| Mode B | **Engineering Complete — Runtime Validation Deferred** — [final report](../pp-01b-mode-b-final-report.md); disposable stack destroyed 2026-08-05 |
| SPIKE-02 Mode B PostGIS | **DEFERRED** (regional vCPU quota) |
| Public production | **NO-GO** |
| Municipal production | **DISABLED** |

### Mode B deferred unblock (minimum)

1. France Central Total Regional vCPUs raised (or free ≥1 vCPU for `Standard_B1s`)  
2. New Mode B authorization + cost ceiling + cleanup deadline  
3. Recreate disposable private stack; create probe; run DNS/TLS proofs  
4. Full teardown; cost recorded  

### EXTERNAL VERIFICATION (revalidate before Mode B apply)

- Private networking: https://learn.microsoft.com/en-us/azure/postgresql/network/concepts-networking-private
- TLS connect: https://learn.microsoft.com/en-us/azure/postgresql/security/security-tls-how-to-connect

Do **not** commit private keys, certificates, credentials, env files, Docker volumes, or logs containing connection strings.
