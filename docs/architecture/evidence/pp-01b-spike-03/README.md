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
| Mode B | **HOLD — NOT EXECUTED** — no Azure CLI/credentials; cost ceiling/SKU UNKNOWN; **no** Azure provisioned |
| SPIKE-02 Mode B | **not executed** |
| Public production | **NO-GO** |
| Municipal production | **DISABLED** |

### Mode B unblock (minimum)

1. Azure CLI (or equivalent) on validation host  
2. Non-production sandbox subscription auth (no production credentials)  
3. Approved cost ceiling + cleanup deadline + temporary prefix  
4. Region/SKU revalidation on Microsoft Learn at apply time  
5. Re-run Mode B; full teardown; cost recorded  

### EXTERNAL VERIFICATION (revalidate before Mode B apply)

- Private networking: https://learn.microsoft.com/en-us/azure/postgresql/network/concepts-networking-private
- TLS connect: https://learn.microsoft.com/en-us/azure/postgresql/security/security-tls-how-to-connect

Do **not** commit private keys, certificates, credentials, env files, Docker volumes, or logs containing connection strings.
