# Parking Session Web — Capability Status

**Last updated:** 2026-07-25 (hosted-beta attempt)  
**Scope:** Web application (`frontend/apps/web`) Parking Session feature after PR1–PR6.

## Layer status

| Layer | Status |
|---|---|
| Backend (parking-service) | Complete in repository (lifecycle, history, delete) |
| Shared SDK / types / contracts | Complete |
| mobile-v2 | Complete |
| **Web (PR1–PR6)** | **Complete locally** (uncommitted working tree) |
| Hosted-beta smoke | **`DEPLOYMENT_NOT_PERFORMED`** — see below |

## Hosted-beta validation attempt (2026-07-25)

**Final classification: `DEPLOYMENT_NOT_PERFORMED`**

### Deployment identity (before)

| Item | Value |
|---|---|
| Local branch | `master` (tracks `origin/master`) |
| Local HEAD SHA | `34e54e76e0f85663634a657782e6fe984c2924d6` |
| HEAD subject | Gemini replay experiment follow-up — **not** Parking Session Web |
| Working tree | **Dirty** (~36 paths); PR1–PR6 web/docs live only in the working tree |
| Web package | `@parkio/web@1.0.0-rc1` |
| Local `deploy-artifacts/current.json` | **Missing** |
| `docker/.env.azure-hosted-beta` | **Missing** (only `.example` present) |
| `docker/.env` | Present with `PARKIO_ENVIRONMENT=local` — refused by hosted-beta preflight |

### Currently deployed hosted-beta (observed without SSH)

| Check | Result |
|---|---|
| `https://api.parkio.dev/actuator/health` | `{"status":"UP",...}` |
| `https://api.parkio.dev/actuator/info` | `{}` (no git/image identity exposed) |
| `https://app.parkio.dev/` | 200; entry chunk `index-DJisIBDM.js` → `MapPage-CM7584F3.js` / `ProfilePage-D9BidpKb.js` |
| Deployed Map/Profile/keys markers for Parking Session Web | **Absent** (`ParkedCar`, `parkingHistory`, `sessionsRoot`, etc. not found) |
| Local production build markers | **Present** (`ParkedCar`, `parkingHistory`, `parking-history`, `sessionsRoot`, `activeSession`) |
| Unauthenticated session routes | `GET/DELETE …/sessions/active|history|{id}` → **401** (routes exist; not 404) |
| SSH `*@20.199.17.76` | **Permission denied (publickey)** for azureuser/parkio/ubuntu/admin |
| Azure CLI (`az`) | Not available on this workstation |

**Mismatch:** live web UI does **not** include Parking Session Web PR1–PR6. Deploying current HEAD would also **not** ship those changes (they are uncommitted). Even `--allow-dirty` cannot proceed without a real azure-hosted-beta env file and VM access.

### Pre-deploy local gates (re-confirmed this attempt)

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
| Web UI smoke (guest → logout privacy) | **Not run** — PR1–PR6 not on deployed frontend; no deploy |
| Responsive / regression / console review | **Not run** |

### Blockers (ordered)

1. No authorized SSH (or Azure CLI / runner) to the hosted-beta VM (`20.199.17.76`).
2. No `docker/.env.azure-hosted-beta` on this workstation (secrets live on-VM per runbook).
3. PR1–PR6 are **uncommitted**; HEAD is unrelated. Commit (or explicit dirty deploy policy on the VM) required before a truthful image tag can represent Parking Session Web.
4. After deploy: re-run `./scripts/smoke-parking-session-hosted-beta.sh` — prior DELETE HTTP 500 must be cleared before `HOSTED_BETA_COMPLETE`.

### Recommended next action

On an operator machine with VM access: commit PR1–PR6 (or deploy from a commit that contains them), sync `/opt/parkio`, run `deploy-hosted-beta.sh` with `docker/.env.azure-hosted-beta`, verify image SHAs + Flyway, re-run parking-session API smoke (especially DELETE), then web manual smoke. Do not mark complete while DELETE 500 or undeployed web remains.

## Web capabilities delivered

1. **Data layer (PR1)** — query keys under `parkingKeys.sessionsRoot`, active + infinite history options, start/complete/cancel/delete/delete-history mutations, logout/user-switch teardown, conflict classifiers.
2. **ACTIVE restore (PR2)** — card + parked-car marker + shared focus; guests never hit private endpoints.
3. **Park Here (PR3)** — geolocation → start mutation; 409 reconcile; post-logout geo abort (PR6).
4. **Active actions (PR4)** — Find my car, Open in Maps, Share, I'm leaving, Cancel session.
5. **Parking History (PR5)** — Profile section `parking-history`, pagination, map/share/delete, delete-all without touching ACTIVE.
6. **Stabilization (PR6)** — a11y focus after history delete, marker without ping, Active card confirm density, i18n preferred terms, floating recenter when spot selected + ACTIVE, invalid-coords Active recovery path.

## Privacy

- Precise coordinates are never shown in UI or toasts.
- Session IDs are never shown in UI.
- External map/share URLs contain coordinates only (HTTPS OSM), never session/user IDs.
- `parkingKeys.sessionsRoot()` is cleared on logout / user switch.
- Delete-all history invalidates history only — ACTIVE cache preserved.

## Product boundaries

Parking Session remains distinct from Smart Return and Community Claim (separate keys, cards, labels, mutations).

## Hosted-beta policy

Do **not** mark hosted-beta complete unless smoke scenarios (guest gate, start, restore, actions, history delete/delete-all, logout privacy, Smart Return + community regression) pass against the deployed environment.

If DELETE returns HTTP 500 on hosted-beta:
- collect method/path, safe body, correlation ID, gateway + parking-service logs, deployed image/commit, Flyway version, route/OpenAPI evidence
- classify cause (stale image, gateway mismatch, missing migration, contract mismatch, DB issue, auth, genuine defect, or unknown) — do **not** label “environment lag” without evidence
- do not add client workarounds that hide backend defects

## Local quality gates (PR6)

Run from `frontend/apps/web`:

- focused Parking Session / Profile / map tests
- Smart Return Profile regression
- `npm run typecheck`
- `npm run lint`
- `npm test -- --run`
- `npm run build` (production)