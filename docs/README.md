# docs

Architecture, operations, certification, and release documentation for Parkio.

## Layout

| Path | Contents |
|------|----------|
| [`ai-context/`](ai-context/) | Contributor rules — architecture, security, service boundaries |
| [`architecture/`](architecture/) | System context, production-readiness plan |
| [`beta/`](beta/) | Hosted-beta deploy and mobile release runbooks |
| [`operations/`](operations/) | VPS checklist, backups, DR, supply chain, runtime sizing |
| [`certification/`](certification/) | Frontend certification reports (F2, FFINAL) |
| [`releases/`](releases/) | RC1 release notes, checklist, known issues, readiness |

Service contracts (OpenAPI, event schemas) belong in documentation — **not** as shared code between microservices.

## Release candidate

Current target: **v1.0.0-rc1** — see [`releases/RC1-READINESS.md`](releases/RC1-READINESS.md).
