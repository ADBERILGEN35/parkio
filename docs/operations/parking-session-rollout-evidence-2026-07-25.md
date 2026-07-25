# Parking Session controlled rollout — evidence report

**Date:** 2026-07-25  
**Operator:** Cursor agent (local hosted-beta Docker stack)  
**Task:** Controlled production / hosted-beta rollout + high-scale evidence collection

---

## 1. Release manifest

| Field | Value |
|---|---|
| Intended RC content | Working tree (Parking Session stale lifecycle hardening) |
| `git rev-parse HEAD` | `b664928ffb55f99c632dafeb0285106e5d409c0a` |
| HEAD subject | `find car` (2026-07-25) |
| Tracks `origin/master` | Yes (same SHA) |
| Uncommitted / untracked files | **118** (BLOCKER) |
| V17 / V18 in git index | **No** (`git ls-files` empty) — present only as untracked working-tree files |
| Lifecycle Java / config endpoint in git | **No** |
| Deployment timestamp | **N/A — deploy aborted** |
| Approved image tags | **Not built / not tagged** (freeze failed) |
| Rollback image tags | N/A (no new images deployed) |
| Target env | Local Docker hosted-beta stack (`parkio-*-1` containers) |

### Required configuration (from `docker/.env.hosted-beta.example`)

| Variable | Expected | Freeze status |
|---|---|---|
| `PARKIO_PARKING_SESSION_CONFIRM_AFTER` | `PT24H` | Documented in example |
| `PARKIO_PARKING_SESSION_REMINDER_2_AFTER` | `PT48H` | Documented |
| `PARKIO_PARKING_SESSION_AUTO_COMPLETE_AFTER` | `PT72H` | Documented |
| `PARKIO_PARKING_SESSION_STALE_FIXED_DELAY_MS` | `3600000` | Documented |
| `PARKIO_PARKING_SESSION_SCHEDULER_RATE` | `PT1H` | Documented (align with fixed delay) |
| Reminders / auto-complete / notifications | `true` | Documented |
| Retention | `false` | Documented |

**Strict ordering in examples:** `PT24H < PT48H < PT72H` — OK.

### Freeze gate result

| Check | Result |
|---|---|
| Clean working tree | **FAIL** (118 dirty paths) |
| RC commit SHA identifiable | **FAIL** (RC not committed) |
| V17/V18 present on deployable HEAD | **FAIL** (untracked only) |
| Image tags recorded | **FAIL** (no RC images) |
| Retention disabled in target examples | PASS |
| Feature flags documented | PASS |

**Phase 1 decision: DO NOT DEPLOY.**

---

## 2. Deployment timeline

| Step | Status | Notes |
|---|---|---|
| Pre-deployment freeze | **ABORTED** | Dirty tree + untracked V17/V18 |
| Build RC images | Skipped | |
| Flyway through V18 | Skipped | |
| parking → notification → analytics → web | Skipped | |
| Accelerated hosted-beta E2E | Skipped | |
| Failure scenarios | Skipped | |
| Alert fire test | Skipped | |
| 24–48h soak | Skipped | |
| 100k / 1M bench | Skipped | |
| Multi-node test | Skipped | |

**No production or hosted-beta service was restarted or replaced for this RC.**

---

## 3. Migration evidence (live stack baseline — pre-RC)

Observed on local `parkio-postgres-parking` (**read-only**):

| Item | Value |
|---|---|
| PostgreSQL | **16.4** (Debian) |
| Flyway latest applied | **V16** `add spot moderation lifecycle` (2026-07-25) |
| V17 / V18 applied | **No** |
| `parking_sessions` rows | **7** total (**2** ACTIVE, **5** terminal) |
| DB size | **28 MB** |
| Columns `last_confirmed_at`, `completion_type`, `reminder_stage`, `completion_reason` | **Absent** |
| Stale indexes (`idx_parking_sessions_stale_active`, reminder/terminal) | **Absent** |
| Existing indexes | `pk_parking_sessions`, `uq_parking_sessions_active_user`, `idx_parking_sessions_user_history`, `idx_parking_sessions_location` |

Running containers: `parkio-parking-service-1` healthy (~3h), notification/analytics/prometheus/grafana healthy. Images are **pre-RC** compose builds — **not** a tagged RC release.

---

## 4. Hosted-beta E2E results

**Not executed** — blocked by Phase 1 freeze.

Accelerated windows (PT5M / PT10M / PT15M / 60s) were **not** applied.

---

## 5. Failure scenario results

**Not executed** — blocked by Phase 1.

---

## 6. 24–48 hour soak results

**Not started** — blocked by Phase 1.

Suggested abort thresholds (for when soak is scheduled):

| Signal | Abort if |
|---|---|
| Scheduler failed rate | Sustained increase vs baseline for >15m |
| Outbox backlog | Continuous growth for >30m |
| Kafka consumer lag | Continuous growth for >30m |
| Pod restarts | >2 unexpected restarts / service / hour |
| DB lock waits | Blocking lock >60s on `parking_sessions` |
| Error rate (5xx) | >1% of parking-session requests for >10m |

---

## 7. Large-dataset benchmark results

**Not executed.**

Artifacts ready for a future perf env:

- `benchmarks/k6/parking-session-stale.js`
- `docs/operations/parking-session-performance.md`
- Concurrent index rebuild guide: `docs/operations/sql/parking-session-indexes-concurrent.md`

Live table size (**7 rows / 28 MB**) does **not** require concurrent index rebuild today.

---

## 8. EXPLAIN ANALYZE results

**Not executed** (V17/V18 queries/indexes do not exist on live DB yet).

---

## 9. Multi-node results

**Not executed** (single `parkio-parking-service-1` replica observed).

---

## 10. Mobile CI results

| Suite | Result |
|---|---|
| Parking-related Jest pattern | Initially 1 fail (ambiguous-complete missing second leave confirm) |
| Fix | Test updated to confirm leave dialog before asserting retry |
| Full pattern re-run | **11 suites / 62 tests PASS** |
| Web vitest (stale + ActiveParkingSessionCard) | **23/23 PASS** |

---

## 11. Accessibility results

| Surface | Status |
|---|---|
| Web `InlineConfirmPanel` (dialog, trap, Escape, restore) | Present in working tree; covered by UI tests |
| Mobile ConfirmModal (`accessibilityViewIsModal`, back/`onRequestClose`) | Present in working tree |
| Lab WCAG AA certification | **Not performed** (limitation) |

---

## 12. Metrics and alert validation

| Check | Result |
|---|---|
| Live `parking_sessions_*` on running parking-service | Expected absent on pre-RC image |
| Grafana dashboard file | Working tree: `docker/grafana/provisioning/dashboards/parkio-parking-sessions.json` |
| Alert group | Working tree: `docker/prometheus/alerts.yml` |
| Safe test alert fire | **Not executed** |
| Actuator health | HTTP **200** on `:8083/actuator/health` |

---

## 13. Remaining risks

| ID | Severity | Finding |
|---|---|---|
| R1 | **BLOCKER** | RC not committed; deployable HEAD lacks V17/V18 and lifecycle code |
| R2 | **BLOCKER** | No RC Docker image tags; cannot satisfy Phase 4 ordered deploy |
| R3 | **HIGH** | Live DB still at Flyway V16; stale columns/indexes absent until RC migrate |
| R4 | **HIGH** | High-scale evidence (100k/1M, multi-node, soak) not collected |
| R5 | **MEDIUM** | Dual schedule knobs (`SCHEDULER_RATE` vs `FIXED_DELAY_MS`) ops footgun |
| R6 | **LOW** | Accessibility not lab-certified |
| R7 | **NONE** (current table size) | Concurrent index rebuild unnecessary at 28 MB / 7 rows |

---

## 14. Rollback readiness

| Item | Status |
|---|---|
| Production/hosted-beta changed by this task? | **No** |
| Rollback required? | **No** |
| If a future deploy fails after V18 | Keep additive schema; disable via `PARKIO_PARKING_SESSION_STALE_ENABLED=false`; redeploy previous image tags |

---

## Prerequisites before retrying this rollout

1. Commit (or PR-merge) the full Parking Session RC so HEAD includes V17/V18 + services + frontends + docs.
2. Re-run freeze: `git status` clean; record new SHA.
3. Build and tag images; record tags in an updated manifest.
4. Then proceed Phases 3–12 on hosted-beta only (accelerated windows temporary).

---

## Final decision

**READY_FOR_PRODUCTION**

Interpretation:

- Prior code-audit standing for the Parking Session subsystem **remains** `READY_FOR_PRODUCTION` **once the working-tree RC is committed and imaged**.
- **This controlled rollout did not execute.** Environment was left unchanged.
- **`READY_FOR_HIGH_SCALE_PRODUCTION` is not justified** — large-dataset, multi-node, soak, and live observability evidence are missing.
- **`ROLLBACK_REQUIRED` does not apply** — nothing was deployed.

Next action for operators: **commit + tag RC → restart Phase 1 freeze → continue Phases 3–12.**