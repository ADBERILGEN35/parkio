# Parkio — Local Beta Tester Runbook

Run the full Parkio stack (backend + gateway + frontend) locally and exercise the
core beta loop. This is a **local development** setup — never reuse these values or
ports in production.

---

## 1. Prerequisites

- **Docker Desktop** (Engine 24+, Compose v2) — running before you start.
- **Node.js ≥ 20** and **pnpm 9** (`corepack enable`, or use `npx pnpm@9.15.0 ...`)
  for the frontend dev server.
- **Java is NOT required** for the Docker path — each service image builds itself
  via the Gradle wrapper inside Docker.
- Internet access on first run (Docker pulls base images; the map uses OpenStreetMap tiles).

---

## 2. Environment setup (one time)

From the repo root:

```powershell
cd docker
Copy-Item .env.example .env
```

### Generate the JWT signing key (required)

`auth-service` signs RS256 access tokens; without a private key it cannot mint
tokens and **login/register fail**. Generate a PKCS#8 RSA key and put it in `.env`
as a double-quoted value with `\n` escapes.

If you have OpenSSL:

```powershell
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 > jwt_key.pem
```

No OpenSSL? Use the Docker image instead:

```powershell
docker run --rm alpine/openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 > jwt_key.pem
```

Then fold it into `.env` (PowerShell):

```powershell
$pem = (Get-Content jwt_key.pem) -join "\n"
(Get-Content .env) | ForEach-Object {
  if ($_ -match '^PARKIO_JWT_PRIVATE_KEY_PEM=') { "PARKIO_JWT_PRIVATE_KEY_PEM=`"$pem`"" } else { $_ }
} | Set-Content .env -Encoding ascii
Remove-Item jwt_key.pem
```

### Other `.env` values (defaults are fine locally)

- `PARKIO_GATEWAY_INTERNAL_SECRET` — has a local-dev default.
- `PARKIO_CORS_ALLOWED_ORIGINS=http://localhost:5173` — matches the Vite dev server.
- `PARKIO_MEDIA_STORAGE_ENDPOINT=http://host.docker.internal:9000` — host embedded in
  signed photo URLs; must be reachable from your browser (see caveats).
- DB passwords / `MINIO_ROOT_PASSWORD` / `GRAFANA_ADMIN_PASSWORD` — local-dev defaults.

> The template already includes per-service Postgres settings, including
> `POSTGRES_AIVALIDATION_*` (port 5440). If you copied an **older** `.env`, re-copy it
> or add the `POSTGRES_AIVALIDATION_*` block or the stack will refuse to start.

---

## 3. Start the stack

```powershell
cd docker

# Infrastructure (databases, Redis, Kafka, MinIO, observability)
docker compose up -d
docker compose ps          # wait until all show "healthy"

# Application services + gateway (first build ~5-6 min — it compiles 10 services)
docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.apps.yml ps
```

Give Spring ~30-60 s after the containers report "Up" before hitting the API.

### Frontend (separate terminal)

```powershell
cd frontend
pnpm install              # or: npx pnpm@9.15.0 install
pnpm dev                  # or: npx pnpm@9.15.0 dev
```

Open <http://localhost:5173>.

---

## 4. Core beta smoke checklist (browser)

1. **Register** a new account.
2. **Wait 1-5 seconds** (profile is provisioned asynchronously via Kafka).
3. **Login**.
4. **Profile** loads (Impact Hub).
5. **Map** loads; allow geolocation or enter coordinates.
6. **Upload a photo + Create a spot** (use a real image; the same image twice is
   rejected as a duplicate).
7. **Spot detail** opens and the **photo renders** via a signed URL.
8. **Second user**: register another account, then **Verify** and **Claim** the spot.
9. **Gamification** points update (owner earns points for upload/verify/claim).

A scripted version of this flow lives in the team's smoke notes; the manual path
above is the canonical tester checklist.

---

## 5. Grant a MODERATOR / ADMIN role

There is no UI to elevate roles. After the user has registered, grant via SQL
(emails are stored **lowercased**):

```powershell
docker exec parkio-postgres-auth psql -U parkio_auth -d parkio_auth -c "INSERT INTO auth_user_roles (user_id, role_id) SELECT u.id, r.id FROM auth_users u, roles r WHERE u.email='you@example.com' AND r.name='MODERATOR' ON CONFLICT DO NOTHING;"
```

Use `ADMIN` instead of `MODERATOR` for admin. **Log out and back in** to get a token
carrying the new role, then `/moderation` and `/analytics` open.

### Scripted: seed full test accounts (USER / MODERATOR / ADMIN)

For real-stack E2E (or just to get pre-verified elevated accounts), use the idempotent
seed script instead of the manual SQL above. It creates ACTIVE, email-verified accounts
with the exact role set, hashing the password in-DB (pgcrypto BCrypt) so it is never
printed or passed on a command line:

```bash
export PARKIO_REAL_USER_EMAIL=user@real-e2e.parkio.local      PARKIO_REAL_USER_PASSWORD='StrongParkio123'
export PARKIO_REAL_MODERATOR_EMAIL=moderator@real-e2e.parkio.local PARKIO_REAL_MODERATOR_PASSWORD='StrongParkio123'
export PARKIO_REAL_ADMIN_EMAIL=admin@real-e2e.parkio.local    PARKIO_REAL_ADMIN_PASSWORD='StrongParkio123'
PARKIO_ENV_FILE=docker/.env ./scripts/seed-real-e2e.sh --target hosted-beta
```

Re-run any time (idempotent); add `--update-passwords` to reset a password. Tidy up the
data a run creates with `./scripts/cleanup-real-e2e.sh` (`--accounts` also drops the
users). Both refuse `--target production` unless `PARKIO_CONFIRM_PRODUCTION=I_UNDERSTAND`.
See `frontend/README.md` §"Real-stack E2E" for details.

---

## 6. Known caveats

- **Provisioning delay:** immediately after register, `/auth/me` and other calls may
  return `ACCOUNT_NOT_ACTIVE`/404 for 1-5 s until the `UserRegistered` event is
  consumed. Wait/refresh — it self-resolves.
- **Lowercased emails:** `Foo@Example.com` is stored as `foo@example.com`; use the
  lowercase form in SQL.
- **`host.docker.internal` signed URLs:** photo URLs embed `host.docker.internal:9000`.
  This resolves in the **browser** on Docker Desktop (Windows/Mac). On Linux hosts (or
  some locked-down Windows networks) it may not resolve — add a hosts entry or override
  `PARKIO_MEDIA_STORAGE_ENDPOINT`.
- **First build is slow** (~5-6 min) because all 10 Java services compile in Docker.
- **OpenStreetMap tiles** need internet; offline = blank map (the rest still works).
- **Empty map on a fresh DB:** there are no seeded spots — create one first.
- **ai-validation-service** is advisory only; its read endpoints return empty lists
  until advisory events are produced. This does not block the core loop.

---

## 7. Cleanup

```powershell
cd docker
docker compose -f docker-compose.yml -f docker-compose.apps.yml down    # stop, keep data
docker compose -f docker-compose.yml -f docker-compose.apps.yml down -v # stop + delete all volumes (fresh start)
```

---

## 8. Troubleshooting

**Gateway health**

```powershell
curl http://localhost:8080/actuator/health        # expect {"status":"UP",...}
```

**JWKS through the gateway** (confirms gateway↔auth + that the JWT key loaded)

```powershell
curl http://localhost:8080/api/v1/auth/.well-known/jwks.json   # expect a "keys" array
```

**Service logs** (substitute the service name)

```powershell
docker compose -f docker-compose.yml -f docker-compose.apps.yml logs gateway-service --tail 80
docker compose -f docker-compose.yml -f docker-compose.apps.yml logs auth-service --tail 80
docker compose -f docker-compose.yml -f docker-compose.apps.yml ps
```

**MinIO check** (bucket should exist and be private)

```powershell
curl http://localhost:9000/minio/health/live      # expect HTTP 200
docker logs parkio-minio-setup                     # "MinIO bucket 'parkio-media' ready."
```
MinIO console: <http://localhost:9001> (`MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`).

**Kafka / profile-provisioning wait**

If `/users/me` 404s right after register, the `UserRegistered` event hasn't been
consumed yet. Wait a few seconds and retry. To inspect:

```powershell
docker compose -f docker-compose.yml -f docker-compose.apps.yml logs user-service --tail 50
docker compose -f docker-compose.yml -f docker-compose.apps.yml logs kafka --tail 30
```

**Login/register failing with token errors** — almost always a missing/invalid
`PARKIO_JWT_PRIVATE_KEY_PEM`. Re-check section 2, then recreate auth-service:

```powershell
docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d auth-service
```

**A service restart-loops** — check its log tail for `Connection refused` (its
database/dependency isn't ready or isn't wired). Confirm the matching
`postgres-*` container is healthy.
