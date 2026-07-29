# Parking Session Web — Capability Status

**Last updated:** 2026-07-29 (source commit status reconciled; hosted evidence unchanged)
**Scope:** Web application (`frontend/apps/web`) Parking Session feature (WP-07.2).

## Layer status

| Layer | Status |
|---|---|
| Backend (parking-service) | Complete in repository (lifecycle, history, delete + `ParkingHistoryDeleted`) |
| Shared SDK / types / contracts | Complete |
| mobile-v2 | Complete |
| **Web (WP-07.2)** | **Complete in committed source** on `master` / `decision` (not worktree-only) |
| Hosted-beta smoke | **`DEPLOYMENT_NOT_PERFORMED`** — R27 remains **FAIL** until immutable-image deploy + smoke exit 0 |

**Distinction:** Web implementation is committed in Git. Hosted-beta deployment has **not** been performed for this implementation. Live `app.parkio.dev` was **not** proven to contain Parking Session Web markers as of the 2026-07-25 attempt.

## Source commit status (2026-07-29)

Web ParkingSession UI and data layer are present at HEAD on branch `decision` (and on `master` ancestry), including for example:

- `frontend/apps/web/src/components/parking/ParkHereStartControl.tsx`
- `frontend/apps/web/src/components/parking/ActiveParkingSessionCard.tsx`
- `frontend/apps/web/src/pages/profile/ParkingHistoryCard.tsx`
- `frontend/apps/web/src/data/mutation-options/parkingSession.ts`
- `frontend/apps/web/src/data/query-options/parking.ts`
- `frontend/apps/web/src/data/SessionQueryCacheSync.tsx`

Landed via earlier commits (including `b664928` / `2b94b20` lineage). Source commit is **no longer** a blocker for hosted deploy.

## Hosted-beta validation attempt (2026-07-25) — historical evidence

**Final classification: `DEPLOYMENT_NOT_PERFORMED`**

This section records the 2026-07-25 operator attempt. It is **not** rewritten as a successful deploy. At that time, local worktree state was dirty and some Web paths were still uncommitted on that workstation; that local condition is **historical** and does not describe current branch HEAD.

### Deployment identity (before) — as observed 2026-07-25

| Item | Value |
|---|---|
| Local branch | `master` (tracks `origin/master`) |
| Local HEAD SHA | `34e54e76e0f85663634a657782e6fe984c2924d6` |
| HEAD subject | Gemini replay experiment follow-up — **not** Parking Session Web |
| Working tree (then) | **Dirty** (~36 paths); some Web PR paths were still uncommitted **on that workstation** |
| Web package | `@parkio/web@1.0.0-rc1` |
| Local `deploy-artifacts/current.json` | **Missing** |
| `docker/.env.azure-hosted-beta` | **Missing** (only `.example` present) |
| `docker/.env` | Present with `PARKIO_ENVIRONMENT=local` — refused by hosted-beta preflight |

### Currently deployed hosted-beta (observed without SSH) — 2026-07-25

| Check | Result |
|---|---|
| `https://api.parkio.dev/actuator/health` | `{"status":"UP",...}` |
| `https://api.parkio.dev/actuator/info` | `{}` (no git/image identity exposed) |
| `https://app.parkio.dev/` | 200; entry chunk `index-DJisIBDM.js` → `MapPage-CM7584F3.js` / `ProfilePage-D9BidpKb.js` |
| Deployed Map/Profile/keys markers for Parking Session Web | **Absent** (`ParkedCar`, `parkingHistory`, `sessionsRoot`, etc. not found) |
| Local production build markers (then) | **Present** (`ParkedCar`, `parkingHistory`, `parking-history`, `sessionsRoot`, `activeSession`) |
| Unauthenticated session routes | `GET/DELETE …/sessions/active|history|{id}` → **401** (routes exist; not 404) |
| SSH `*@20.199.17.76` | **Permission denied (publickey)** for azureuser/parkio/ubuntu/admin |
| Azure CLI (`az`) | Not available on this workstation |

**Mismatch (2026-07-25):** live web UI did **not** include Parking Session Web markers. Deploy could not proceed without a real azure-hosted-beta env file and VM access.

### Pre-deploy local gates (re-confirmed that attempt)

| Gate | Result |
|---|---|
| `npm run typecheck` | PASS |
| `npm run lint` | PASS (0 errors / 5 pre-existing react-refresh warnings) |
| Focused Parking Session / Profile / Map tests | PASS — 157 tests / 20 files |
| Full web suite | PASS — **615** tests / 84 files |
| `npm run build` | PASS (~3.3s) |

### Deployment

| Item | Result |
|---|---|
| Canonical command attempted conceptually | `PARKIO_ENV_FILE=docker/.env.azure-hosted-beta ./scripts/deploy-hosted-beta.sh` |
| Actual deploy | **Not started** |
| Preflight with `docker/.env` | FAIL — 33 failures (local profile; expected) |
| Deployed commit / images / Flyway | **Not verified** (no SSH / no deploy artifacts) |

### API / web / privacy smoke

| Area | Result |
|---|---|
| Authenticated API smoke (start/complete/cancel/history/DELETE) | **Not run** — no disposable smoke credentials in environment |
| Prior evidence (`ps-s1p012-20260724T212710Z`) | Lifecycle mostly PASS; **DELETE single + bulk → HTTP 500** on then-deployed revision |
| Web UI smoke (guest → logout privacy) | **Not run** — Web markers absent on deployed frontend; no deploy |
| Responsive / regression / console review | **Not run** |

### Blockers (ordered) — updated for current source

1. No authorized SSH (or Azure CLI / runner) to the hosted-beta VM (`20.199.17.76`).
2. No `docker/.env.azure-hosted-beta` on this workstation (secrets live on-VM per runbook).
3. ~~Web PR1–PR6 uncommitted~~ — **resolved in source** (Web WP-07.2 committed on `master` / `decision`). Source commit is no longer the blocker.
4. After deploy: run `./scripts/smoke-parking-session-hosted-beta.sh` to exit 0 on an immutable `sha-<gitsha>` image that includes Parking Session Web + deletion APIs/analytics. Prior DELETE HTTP 500 must be cleared before `HOSTED_BETA_COMPLETE`. R27 remains **FAIL** until then.

### Recommended next action

On an operator machine with VM access: sync `/opt/parkio` to a release SHA that contains Web ParkingSession (and preferably WP-07.3 deletion analytics), run `deploy-hosted-beta.sh` with `docker/.env.azure-hosted-beta`, verify image SHAs + Flyway, re-run parking-session API smoke (especially DELETE), then web manual smoke. Do not mark R27 / hosted-beta complete while smoke exit 0 is unproven.

## Web capabilities delivered (committed source)

1. **Data layer** — query keys under `parkingKeys.sessionsRoot`, active + infinite history options, start/complete/cancel/confirm/delete/delete-history mutations, logout/user-switch teardown, conflict classifiers.
2. **ACTIVE restore** — card + parked-car marker + shared focus; guests never hit private endpoints.
3. **Park Here** — geolocation → start mutation; 409 reconcile; post-logout geo abort.
4. **Active actions** — Find my car, Open in Maps, Share, I'm leaving, Cancel session.
5. **Parking History** — Profile section `parking-history`, pagination, map/share/delete, delete-all without touching ACTIVE.
6. **Stabilization** — a11y focus after history delete, marker without ping, Active card confirm density, i18n preferred terms, floating recenter when spot selected + ACTIVE, invalid-coords Active recovery path.

## Privacy

- Precise coordinates are never shown in UI or toasts.
- Session IDs are never shown in UI.
- External map/share URLs contain coordinates only (HTTPS OSM), never session/user IDs.
- `parkingKeys.sessionsRoot()` is cleared on logout / user switch.
- Delete-all history invalidates history only — ACTIVE cache preserved.

## Product boundaries

Parking Session remains distinct from Smart Return and Community Claim (separate keys, cards, labels, mutations).

## Hosted-beta policy

Do **not** mark hosted-beta / R27 complete unless smoke scenarios (guest gate, start, restore, actions, history delete/delete-all, logout privacy, Smart Return + community regression) pass against the deployed environment.

If DELETE returns HTTP 500 on hosted-beta:
- collect method/path, safe body, correlation ID, gateway + parking-service logs, deployed image/commit, Flyway version, route/OpenAPI evidence
- classify cause (stale image, gateway mismatch, missing migration, contract mismatch, DB issue, auth, genuine defect, or unknown) — do **not** label “environment lag” without evidence
- do not add client workarounds that hide backend defects

## Local quality gates

Run from `frontend/apps/web`:

```bash
npm run typecheck
npm run lint
npm run test
npm run build
```
