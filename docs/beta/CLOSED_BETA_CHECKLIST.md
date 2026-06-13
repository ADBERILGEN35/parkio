# Parkio Closed Beta — Checklist

Operational checklist for running the Parkio closed beta with 5–10 testers on a
**local** stack. Full step-by-step commands live in
[`docker/BETA_RUNBOOK.md`](../../docker/BETA_RUNBOOK.md); this page is the
condensed go/no-go list.

> This is a **local development** beta — not a hosted environment. Each tester runs
> the full stack on their own machine, or a maintainer shares a screen / single host.

---

## 1. Maintainer preflight

Do this once before inviting testers, on a clean checkout:

- [ ] Docker Desktop running; `docker info` succeeds.
- [ ] `docker/.env` created from `.env.example` and `PARKIO_JWT_PRIVATE_KEY_PEM` set
      (see runbook §2). `.env` is git-ignored — never commit it.
- [ ] `.env` contains the `POSTGRES_AIVALIDATION_*` block (older copies predate it).
- [ ] Infra boots healthy: `docker compose up -d` → `docker compose ps` all `healthy`.
- [ ] Apps boot: `docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d --build`;
      no container stuck `Restarting`.
- [ ] Gateway health: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`.
- [ ] JWKS via gateway: `curl http://localhost:8080/api/v1/auth/.well-known/jwks.json`
      returns a `keys` array.
- [ ] MinIO bucket ready: `docker logs parkio-minio-setup` shows `bucket 'parkio-media' ready`.
- [ ] Frontend runs: `cd frontend && pnpm install && pnpm dev` → <http://localhost:5173>.
- [ ] Manual happy-path passes once (register → upload → create → photo → verify/claim).
- [ ] (Optional) `pnpm test`, `pnpm e2e`, `pnpm build` all green.
- [ ] Decide how testers connect: each runs locally, or you share one host (note the
      `host.docker.internal` photo caveat below if remote).

## 2. Tester setup

- [ ] Install Docker Desktop and Node.js ≥ 20 (+ `corepack enable`).
- [ ] Clone the repo (or receive the shared bundle).
- [ ] Follow `docker/BETA_RUNBOOK.md` §2–§3 (env + start the stack).
- [ ] Start the frontend (`pnpm dev`) and open <http://localhost:5173>.
- [ ] Keep the feedback template ([`TESTER_FEEDBACK_TEMPLATE.md`](TESTER_FEEDBACK_TEMPLATE.md)) handy.

## 3. Env checklist (the things that actually break startup)

- [ ] `PARKIO_JWT_PRIVATE_KEY_PEM` — **set** (empty → login/register fail).
- [ ] `PARKIO_CORS_ALLOWED_ORIGINS=http://localhost:5173`.
- [ ] `PARKIO_MEDIA_STORAGE_ENDPOINT=http://host.docker.internal:9000`.
- [ ] `PARKIO_GATEWAY_INTERNAL_SECRET` — present (template default is fine locally).
- [ ] `POSTGRES_AIVALIDATION_*` present (port 5440).
- [ ] Frontend `VITE_API_BASE_URL` — default `http://localhost:8080/api/v1` (only override if needed).

## 4. Smoke checklist (each tester, ~20–30 min)

1. [ ] Register a new account.
2. [ ] Wait 1–5 s (async profile provisioning).
3. [ ] Login.
4. [ ] Profile / Impact Hub loads.
5. [ ] Map loads; geolocation or manual coordinates.
6. [ ] Upload a photo + create a spot (use a unique image).
7. [ ] Spot detail opens and the photo renders.
8. [ ] Nearby search finds the new spot.
9. [ ] Second account: verify + claim the spot.
10. [ ] Gamification points update.
11. [ ] (If granted MODERATOR/ADMIN) `/moderation` and `/analytics` open.

## 5. Known caveats (expected — not bugs)

- **Provisioning delay:** brief `ACCOUNT_NOT_ACTIVE`/404 for 1–5 s right after register.
- **Lowercased emails:** stored lowercase; use lowercase in SQL.
- **`host.docker.internal` photos:** signed photo URLs resolve in-browser on Docker
  Desktop (Win/Mac); on Linux or some locked-down networks they may not.
- **First build slow:** ~5–6 min (10 Java services compile in Docker).
- **OpenStreetMap tiles** need internet.
- **Fresh DB = empty map:** create a spot first.
- **ai-validation-service** advisory data is empty until events flow; does not block the loop.

## 6. Rollback / cleanup

```powershell
cd docker
docker compose -f docker-compose.yml -f docker-compose.apps.yml down     # stop, keep data
docker compose -f docker-compose.yml -f docker-compose.apps.yml down -v   # wipe all volumes (fresh start)
```

To reset just the data (fresh accounts/spots) use `down -v`, then start again.

## 7. What to report

Use [`TESTER_FEEDBACK_TEMPLATE.md`](TESTER_FEEDBACK_TEMPLATE.md). For every issue include:

- **What you did** (step) and **what happened** vs. expected.
- **Severity** (Blocker / Major / Minor / Cosmetic).
- **traceId** from any on-screen error message (the UI shows it).
- **Screenshot** of the UI state.
- **Logs** if a service misbehaved:
  `docker compose -f docker-compose.yml -f docker-compose.apps.yml logs <service> --tail 80`.
- Your **environment** (OS, Docker Desktop version, browser).

Do **not** report the items in §5 (known caveats) as bugs.
