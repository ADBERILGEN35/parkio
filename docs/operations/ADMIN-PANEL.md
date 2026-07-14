# Parkio Administration System

> Production-oriented admin panel for hosted beta. Enforced server-side; the UI only reflects roles.

## Architecture

| Concern | Owner |
|---------|--------|
| Admin user/search/sessions/roles/audit/bootstrap | **auth-service** (`/api/v1/admin/**`) |
| Platform KPIs | **analytics-service** (`/api/v1/analytics/**`) |
| Moderation queue | **moderation-service** (`/api/v1/moderation/**`) |
| Edge RBAC | **gateway** (`RouteAuthorizationRules`) |
| Web UI | **frontend/apps/web** `/admin/*` |

Database-per-service is preserved. Auth never queries parking/media DBs; admin UI composes cross-domain views via existing APIs and case-linked IDs.

### Suspend / reactivate sync

Admin status changes apply in auth-service, then emit `UserSuspended` / `UserRestored` on `parkio.moderation.action` (nil moderation case id) so user-service and notification-service stay consistent. Auth inbox-claims the event id so Kafka redelivery is a no-op.

## Roles

| Role | Access |
|------|--------|
| `USER` | No admin APIs or `/admin` UI |
| `MODERATOR` | Moderation queue (`/admin/moderation`), elevated spot/media reads |
| `ADMIN` | Admin panel, analytics, user suspend/reactivate/sessions, grant `MODERATOR` |
| `SUPER_ADMIN` | Everything ADMIN can do + grant/revoke `ADMIN`/`SUPER_ADMIN` |

`SUPER_ADMIN` satisfies every gateway/service check that requires `ADMIN`.

### Privilege safeguards

- ADMIN cannot modify SUPER_ADMIN accounts
- ADMIN cannot grant ADMIN/SUPER_ADMIN
- Cannot remove or demote the final SUPER_ADMIN
- Role changes and state mutations write immutable `admin_audit_events` rows

## First SUPER_ADMIN bootstrap (Azure / VPS)

Never ship default passwords. Promote an **existing ACTIVE + verified** account:

```bash
# On the VPS (Docker network can reach auth-service:8081)
export PARKIO_ADMIN_BOOTSTRAP_ENABLED=true
export PARKIO_ADMIN_BOOTSTRAP_TOKEN='<long-random-secret>'
# recreate auth-service so env is loaded, then:
./scripts/bootstrap-super-admin.sh admin@example.com
```

The script POSTs to `/internal/auth/admin/bootstrap-super-admin` with `X-Gateway-Auth` and `X-Parkio-Admin-Bootstrap-Token`. Disable bootstrap after success (`PARKIO_ADMIN_BOOTSTRAP_ENABLED=false`).

## Web routes

| Path | Role |
|------|------|
| `/admin` | ADMIN+ dashboard |
| `/admin/users`, `/admin/users/:id` | user management |
| `/admin/security` | security summary |
| `/admin/audit` | audit trail |
| `/admin/system` | env / observability guidance |
| `/admin/analytics` | existing analytics page |
| `/admin/moderation` | existing moderation page (MODERATOR+) |

Legacy `/analytics` and `/moderation` redirect into `/admin/*`.

## Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `PARKIO_ADMIN_BOOTSTRAP_ENABLED` | `false` | Allow one-time SUPER_ADMIN bootstrap |
| `PARKIO_ADMIN_BOOTSTRAP_TOKEN` | empty | Shared secret for bootstrap header |
| `PARKIO_RL_ADMIN_REPLENISH` / `BURST` | 20 / 40 | Gateway rate limit for `/api/v1/admin/**` |

## Deployment (Azure hosted-beta)

1. Pull release tag; rebuild **auth-service**, **gateway-service**, and **web** image.
2. Apply Flyway V20 on next auth-service start (`SUPER_ADMIN` role + `admin_audit_events`).
3. Bootstrap SUPER_ADMIN; disable bootstrap env.
4. Smoke: login as admin → `https://app.../admin` → users search → suspend with reason → audit shows event.
5. Confirm USER receives 403 on `/api/v1/admin/dashboard`.

## Rollback

- Frontend-only: redeploy previous web image (API remains compatible if unused).
- Backend: do not drop V20 in place; disable admin routes by redeploying gateway without admin rule only if emergency (prefer feature-flag via removing admin JWT roles instead).

## Known limitations

- No historical fake charts — dashboard uses live counts only.
- Parking/media/notification deep lists require new ADMIN list APIs (not added); use moderation case linkage and Grafana for ops.
- Grafana stays SSH-tunneled (not public).
- Gamification point editing is intentionally out of scope (ledger via moderation sanctions only).

## Security notes

- Authorization is server-side on every admin endpoint.
- Gateway strips client `X-User-*` and re-injects from JWT.
- Internal bootstrap never routed publicly.
- Audit APIs do not allow edit/delete.
- Passwords, token hashes, and raw refresh tokens are never returned.
