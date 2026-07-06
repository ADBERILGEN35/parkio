# Support

## Release status

Parkio **v1.0.0-rc1** is a **hosted-beta release candidate**. It is suitable for controlled operator-run deployments, not unattended public production.

| Track | Status |
|-------|--------|
| Hosted beta | **Supported** — see operator docs below |
| Public production | **Not supported** on this release |

## Documentation

| Topic | Location |
|-------|----------|
| Quick start (backend) | [`README.md`](README.md) |
| Docker / local stack | [`docker/README.md`](docker/README.md) |
| Hosted beta deploy | [`docs/beta/deploy-runbook.md`](docs/beta/deploy-runbook.md) |
| VPS checklist | [`docs/operations/vps-hosted-beta-checklist.md`](docs/operations/vps-hosted-beta-checklist.md) |
| Backup & restore | [`docs/operations/backup-runbook.md`](docs/operations/backup-runbook.md) |
| Disaster recovery | [`docs/operations/disaster-recovery-runbook.md`](docs/operations/disaster-recovery-runbook.md) |
| Mobile release | [`docs/beta/mobile-release.md`](docs/beta/mobile-release.md) |
| Known issues | [`docs/releases/KNOWN-ISSUES.md`](docs/releases/KNOWN-ISSUES.md) |
| RC1 checklist | [`docs/releases/RC1-CHECKLIST.md`](docs/releases/RC1-CHECKLIST.md) |

## Getting help

- **Operators:** Follow runbooks in `docs/beta/` and `docs/operations/`. Run `scripts/preflight-hosted-beta.sh` before every deploy.
- **Security issues:** See [`SECURITY.md`](SECURITY.md) — do not file public issues for vulnerabilities.
- **Bugs:** Open a GitHub issue with reproduction steps, environment (local / hosted-beta), and relevant logs (redact secrets).

## Troubleshooting

| Symptom | First check |
|---------|-------------|
| Services fail to start | JWT key and gateway secret set? (`PARKIO_JWT_PRIVATE_KEY_PEM`, `PARKIO_GATEWAY_INTERNAL_SECRET`) |
| 401 after login | CORS origins match SPA URL? Refresh cookie domain/path correct? |
| Media 403 in browser | `PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT` matches public media domain |
| Upload stuck on SCANNING | ClamAV healthy? `PARKIO_MEDIA_SCANNER_ENABLED=true` |
| Alerts not delivered | Slack webhook or `PARKIO_ALERT_WEBHOOK_URL` configured? Alertmanager running? |
| Mobile push not working | Expo access token set? Dev client vs Expo Go limitations |