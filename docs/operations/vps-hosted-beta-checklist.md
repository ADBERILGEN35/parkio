# VPS hosted-beta deployment checklist (R6.1)

Operator checklist for a **clean Ubuntu 22.04/24.04 VPS**. The canonical
single-path operator guide is [`../../HOSTED-BETA-RUNBOOK.md`](../../HOSTED-BETA-RUNBOOK.md).
Use this file as supporting detail.

## 1. VPS

- Ubuntu 22.04/24.04 LTS, **8 vCPU / 24 GB RAM** recommended
- Static public IP, NTP enabled, unattended security updates

## 2. DNS

A/AAAA records for `PARKIO_WEB_DOMAIN`, `PARKIO_DOMAIN`, `PARKIO_MEDIA_DOMAIN` pointing at the VPS.

## 3. Firewall

```bash
sudo ufw default deny incoming && sudo ufw allow OpenSSH && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw enable
```

Only **22, 80, 443** from the internet. All data and observability ports stay private (hosted-beta overlay).

## 4. Docker

Docker Engine 24+ and Compose v2.24.4+ (`docker compose version`).

## 5. Secrets

```bash
git clone <repo> /opt/parkio && cd /opt/parkio/docker
cp .env.hosted-beta.example .env && chmod 600 .env
```

Replace every `CHANGE_ME`. Store a copy in a secrets manager. Required:

- JWT key, gateway secret, nine DB passwords, Redis, MinIO, Kafka cluster ID
- Resend API key, Expo access token, Slack webhook, Grafana password
- Domains, CORS, `VITE_API_BASE_URL`, `PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT`
- Explicit `PARKIO_OPENAPI_ENABLED=false`, `PARKIO_EMAIL_PROVIDER=resend`,
  `PARKIO_PUSH_DELIVERY_PROVIDER=expo`, `PARKIO_ENVIRONMENT=hosted-beta`
  (the compose defaults for these are unsafe for a hosted environment)

## 5b. Preflight (R-005)

Run the secret/configuration preflight — it validates all of the above plus
domain/URL hygiene and safety toggles, then renders the compose config
(`validate-hosted-beta-compose.sh`):

```bash
cd /opt/parkio && PARKIO_ENV_FILE=docker/.env ./scripts/preflight-hosted-beta.sh
```

Output is grouped (`Secrets`, `Domains & URLs`, `Providers`, `Deployment
safety`, `Compose`); every `FAIL` line names the variable, the problem and a
`fix:` command. Exit 0 = safe to deploy. The deploy script runs this
automatically and refuses to build if it fails, so a placeholder or local-dev
value can never reach the VPS stack. Rules, required formats and fixes:
`../../HOSTED-BETA-RUNBOOK.md` -> "Preflight Checklist".

Intentional deviations (each surfaces as a WARN):

- `PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE=1` — non-default (but real)
  email/push provider; document why in the deploy notes.
- `PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK=1` — run alert-silent
  (Alertmanager via SSH tunnel only).

## 6. Deploy

```bash
cd /opt/parkio && PARKIO_ENV_FILE=docker/.env ./scripts/deploy-hosted-beta.sh
```

The deploy aborts with exit 3 before building any image if the preflight fails.

## 7. TLS

Caddy auto-ACME when DNS and ports 80/443 work. Persist `caddy-data` volume. HSTS and HTTP to HTTPS are in `docker/caddy/Caddyfile`.

## 8. Backups

Nightly cron on `scripts/backup-hosted-beta.sh`. Set `BACKUP_DIR`, optional `BACKUP_ENCRYPT_PASSPHRASE` and `BACKUP_MC_DEST`. RPO ~24h, no PITR.

## 9. Monitoring

SSH tunnel: Grafana :3000, Prometheus :9090, Alertmanager :9093. Set `PARKIO_ALERT_SLACK_WEBHOOK_URL`.

## 10. Rollback

`PARKIO_ENV_FILE=docker/.env ./scripts/rollback-hosted-beta.sh`

## Related

- `docs/operations/backup-runbook.md`
- `docs/operations/disaster-recovery-runbook.md`
- `docs/operations/alert-response-runbook.md`
