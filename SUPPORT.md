# Support

Parkio is a hosted-beta candidate repository. Support is best-effort and focused
on making the project understandable, reproducible, and safe to evaluate.

## Release Status

Parkio **v1.0.0-rc1** is a **hosted-beta release candidate**. It is suitable for controlled operator-run deployments, not unattended public production.

| Track | Status |
|-------|--------|
| Local development | Supported through repository docs and issues |
| Hosted beta | Supported through operator runbooks and preflight scripts |
| Public production | Not supported on this release |
| Broad public open source | Pending final license selection |

## Documentation

| Topic | Location |
|-------|----------|
| Repository overview | [README](README.md) |
| Documentation index | [docs/README.md](docs/README.md) |
| Architecture | [docs/architecture/README.md](docs/architecture/README.md) |
| Docker / local stack | [`docker/README.md`](docker/README.md) |
| Hosted beta deploy | [`docs/beta/deploy-runbook.md`](docs/beta/deploy-runbook.md) |
| VPS checklist | [`docs/operations/vps-hosted-beta-checklist.md`](docs/operations/vps-hosted-beta-checklist.md) |
| Backup & restore | [`docs/operations/backup-runbook.md`](docs/operations/backup-runbook.md) |
| Disaster recovery | [`docs/operations/disaster-recovery-runbook.md`](docs/operations/disaster-recovery-runbook.md) |
| Mobile release | [`docs/beta/mobile-release.md`](docs/beta/mobile-release.md) |
| Known issues | [`docs/releases/KNOWN-ISSUES.md`](docs/releases/KNOWN-ISSUES.md) |
| RC1 checklist | [`docs/releases/RC1-CHECKLIST.md`](docs/releases/RC1-CHECKLIST.md) |

## Getting Help

| Need | Where to go |
|------|-------------|
| Bug report | Use the GitHub bug report template |
| Documentation problem | Use the documentation issue template |
| Scoped product proposal | Use the feature request template |
| Security vulnerability | Follow [SECURITY.md](SECURITY.md), not public issues |
| Hosted-beta operation | Start with [docs/beta/deploy-runbook.md](docs/beta/deploy-runbook.md) |
| Waitlist/privacy deletion request | Use the contact path documented on the beta legal pages before public intake |

Good support requests include the command run, expected result, actual result,
environment, logs with secrets redacted, and the relevant commit or branch.

## Troubleshooting

| Symptom | First check |
|---------|-------------|
| Services fail to start | JWT key and gateway secret set? (`PARKIO_JWT_PRIVATE_KEY_PEM`, `PARKIO_GATEWAY_INTERNAL_SECRET`) |
| 401 after login | CORS origins match SPA URL? Refresh cookie domain/path correct? |
| Media 403 in browser | `PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT` matches public media domain |
| Upload stuck on SCANNING | ClamAV healthy? `PARKIO_MEDIA_SCANNER_ENABLED=true` |
| Alerts not delivered | Slack webhook or `PARKIO_ALERT_WEBHOOK_URL` configured? Alertmanager running? |
| Mobile push not working | Expo access token set? Dev client vs Expo Go limitations |
| Waitlist submission rejected | Consent checked? Email valid? Redis/database configured? Rate limit hit? |

## Response Expectations

There is no guaranteed service-level agreement for public repository support.
Security reports and hosted-beta blockers should be prioritized over general
questions and enhancement ideas.
