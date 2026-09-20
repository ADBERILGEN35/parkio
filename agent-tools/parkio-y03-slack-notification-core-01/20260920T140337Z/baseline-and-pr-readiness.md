# Baseline and PR readiness — PARKIO-Y03

**UTC evidence:** `20260920T140337Z`  
**Package:** PARKIO-Y03-SLACK-NOTIFICATION-CORE-01

## API baseline

| Item | Value |
|------|-------|
| Last reported api | `4a9ba2184e867b8ea8b927f7c3f8bcb137c2760a` |
| Observed `origin/api` | `4a9ba2184e867b8ea8b927f7c3f8bcb137c2760a` |
| Delta | **NONE** |
| Worktree base | `origin/api` @ `4a9ba218…` |
| Feature branch | `y03-slack-notification-core` |

## Dependency packages

| Package | Path | Used |
|---------|------|------|
| Y01 | `agent-tools/parkio-y01-telemetry-event-contract-01/20260920T131333Z/` | Slack catalog + delivery design |
| Y02 | `agent-tools/parkio-y02-new-relic-log-pilot-01/20260920T134500Z/` | `y03-handoff.md` ownership split |

Y02 local mock acceptance is **not** treated as real New Relic acceptance.

## PR #52 (Invite JWT CI fixture)

| Field | Value |
|-------|-------|
| URL | https://github.com/ADBERILGEN35/parkio/pull/52 |
| State | OPEN, **draft** |
| Base OID | `4a9ba2184e867b8ea8b927f7c3f8bcb137c2760a` |
| Head OID | `22158518acfeddcf4c0b932674579d1b5a0579d8` |
| CI (sampled) | Build & unit tests **pass**; security scans **pass**; deploy jobs **skipping** |
| Readiness | Review-ready CI fixture fix; **unmerged** |
| Y03 dependency | **NONE** — not required for Slack biz relay |

## PR #53 (Y02 New Relic log pilot)

| Field | Value |
|-------|-------|
| URL | https://github.com/ADBERILGEN35/parkio/pull/53 |
| Branch | `y02-new-relic-log-pilot` |
| State | OPEN, **draft** |
| Base OID | `4a9ba2184e867b8ea8b927f7c3f8bcb137c2760a` |
| Head OID | `cf86a47c2082078b620b55295ed509ce59cc32cd` |
| CI (sampled) | Build & unit tests **pass**; Config+script checks **pass**; several compose/integration jobs **pending** at capture time |
| Local acceptance | 13/13 mock PASS (prior package) |
| Real NR ingestion | **NOT_EXECUTED** |
| Y03 dependency | **NONE** — Y03 does not copy Fluent Bit / NR pilot code. Log fields from handoff are advisory only for future incident context. |

## Y03 independence

Y03 is an **independent PR** against `api`. No silent copy of #52/#53 implementations.
