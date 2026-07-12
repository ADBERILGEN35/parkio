# Azure Security Checklist

## Launch blockers

- [ ] Subscription owner confirms credit expiry, spending limit, tenant, and break-glass access.
- [ ] SSH source is a single operator public `/32` (or controlled CIDR), not `Internet`.
- [ ] SSH password authentication and root login are disabled; only Ed25519/RSA key auth is present.
- [ ] NSG and UFW expose only 22/TCP restricted, 80/TCP, 443/TCP, and optionally 443/UDP.
- [ ] Compose render confirms no gateway, service, DB, Redis, Kafka, MinIO console/API, ClamAV, Grafana, Prometheus, Loki, Tempo, Alertmanager, or exporter public bind.
- [ ] Real `.env` passes preflight, contains no placeholders, is outside Git, owned by root/operator group, and mode 0640 or stricter.
- [ ] JWT private key, gateway secret, waitlist HMAC secret, ten distinct DB passwords, Redis, MinIO, Grafana, email, push, and alert credentials are unique hosted-beta values.
- [ ] `PARKIO_OPENAPI_ENABLED=false`, token logging false, test hooks false, CORS explicit, secure refresh cookies true.
- [ ] ClamAV remains enabled and fail-closed while media upload is available.
- [ ] Authenticated smoke, direct-port denial, upload scan, backup, and restore evidence exists.

## Host controls

```text
[ ] Ubuntu 24.04 LTS amd64 from Canonical marketplace image
[ ] Automatic security updates enabled; first upgrade and reboot complete
[ ] NTP active
[ ] Dedicated non-root operator; no shared accounts
[ ] sshd: PasswordAuthentication no, PermitRootLogin no, MaxAuthTries 3
[ ] UFW deny incoming / allow outgoing; same narrow rules as NSG
[ ] Docker daemon not exposed over TCP
[ ] Only the operator belongs to docker group; membership treated as root-equivalent
[ ] Repository and env not world-readable
[ ] Data disk mounted with persistent UUID before Docker install/use
[ ] `docker ps` and `ss -lntup` reviewed after every deploy
[ ] OS/disk utilization and security updates reviewed daily
```

Fail2ban is optional defense in depth when SSH is already restricted to `/32`; it is not a substitute for NSG restrictions. Azure Bastion is not cost-justified for one temporary VM. Use a changed operator IP by updating the NSG rule first through Portal/Cloud Shell.

## Container and application controls

The app containers already run as non-root uid 10001, drop all capabilities, set no-new-privileges, bound PIDs/logs, and use readiness/graceful shutdown. Remaining risks:

- Infrastructure images are not uniformly configured as non-root; Docker socket ownership is host-root authority.
- Root filesystems are not read-only; the repo explicitly defers this until upload validation.
- Single-node PostgreSQL, Kafka RF=1, Redis, and MinIO have no HA/PITR.
- Compose does not restart a hung-but-running unhealthy container.
- Services trust gateway-injected identity; any direct service exposure breaks the trust model.

## Secret inventory

Never print values in evidence or shell history.

| Secret group | Variables / material | Rotation action |
|---|---|---|
| JWT signing | `PARKIO_JWT_PRIVATE_KEY_PEM`, key id, additional public keys | publish old public key, rotate signer, drain access-token TTL, remove old key |
| Gateway trust | internal current/accepted secrets | downstream accepts both, flip gateway, then remove old |
| Databases | ten `POSTGRES_*_PASSWORD` values | rotate per service with maintenance validation |
| Redis | `REDIS_PASSWORD` | coordinated service/Redis restart |
| MinIO | root user/password | rotate after off-host object export |
| Waitlist privacy | `PARKIO_WAITLIST_HASH_SECRET` | rotation changes future HMAC linkage; document date |
| Email | Resend API key, from/reply-to | revoke provider key and replace env |
| Push | Expo access token | revoke provider token and replace env |
| Grafana/alerts | admin password, Slack/generic webhook secret | rotate provider and env |
| Backup | encryption passphrase, optional remote credentials | verify old archives before retiring old passphrase |
| Frontend map | MapTiler public key | domain-restrict and rotate at provider |
| Operator | SSH private key, encrypted secret bundle | remove public key from VM; rotate all runtime secrets on exit |

## Secret storage decision

For 30 days, **Key Vault is not justified as the primary runtime mechanism**. Compose consumes environment values; adding Key Vault requires fetch/materialization logic, permissions, managed identity, failure handling, and still leaves a local copy. Use:

1. a root/operator-group owned `docker/.env.azure-hosted-beta` at mode 0640 (0600 when running scripts through sudo),
2. an encrypted offline operator bundle with a separate passphrase,
3. no secret values in Git, manifests, command output, tickets, or screenshots,
4. complete rotation at teardown.

Docker secrets are not natively consumed by the current Spring/Compose wiring. A systemd `EnvironmentFile` does not remove `/proc`/container environment exposure and complicates the canonical scripts. Key Vault becomes recommended if the beta continues, multiple operators are added, or Azure-managed resources need identity-based access.

## Azure services

- Enable free activity/service-health alerts and budget notifications.
- Do not automatically enable paid Defender for Servers, Log Analytics agents, Sentinel, or VM Insights under this credit. Evaluate prices explicitly first.
- A system-assigned managed identity is unnecessary without Key Vault/Storage API access. Avoid unused privilege.
- Encryption at rest is provided by Azure managed disks; application backups must also be encrypted before leaving the VM.
- Rate limiting stays in the gateway/Redis. NSG/UFW are not application-layer abuse controls.

## Privacy checks

- Keep Loki/Tempo disabled in the constrained profile, reducing location/user trace retention.
- Review Docker logs for tokens, email verification links, precise location history, device tokens, and object signed URLs.
- Preserve admin export access only through authenticated gateway roles; never expose service/admin ports.
- Store only required backup sets and destroy them according to the exit plan.
