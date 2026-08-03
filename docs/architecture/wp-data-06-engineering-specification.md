# DATA-WP-06 - Municipal Source Health, SLA and Recovery Operations

> **Naming:** This package is **DATA-WP-06** (municipal/parking data). It is unrelated to
> repository **WP-06** (Operational Platform: staging verification, backup/restore,
> production governance under `docs/operations/wp-06-*`). Document filename follows
> `wp-data-0N-*`. Do not reopen Decision Intelligence `wp-05-*` or ops `wp-06-*`.

## 1. Executive summary

DATA-WP-01 through DATA-WP-05 delivered municipal sources, IZUM live occupancy,
OSM publication, IZELMAN historical inventory, the canonical registry, and bounded
candidate generation. Hosted-beta at `b3f1cec6b7b89082975444ef79d0d3712bdf4315`
(V33) proved candidate generation remains dark and non-mutating.

Post-DATA-WP-05A evidence shows **IZUM synchronization is actively failing**
(`Read timed out` against `openapi.izmir.bel.tr`), public freshness correctly ages
to **STALE** with `availableSpaces` masked, yet operators lack consecutive-failure
tracking, accurate timeout taxonomy, Prometheus alerts, and a dedicated recovery
runbook. **DATA-WP-06** closes that operational gap without enabling registry
linking, IZELMAN publication, or availability fabrication.

**DATA-WP-06A** (separate) validates alerts and recovery on hosted-beta against
live upstream behavior.

## 2. Repository evidence

| Evidence | Finding |
|----------|---------|
| Deployed commit | `b3f1cec6b7b89082975444ef79d0d3712bdf4315` (V33) |
| DATA-WP-05A dry-run | 14 IZUM / 6 pairs / 0 eligible / 0 candidates; no link mutation |
| Hosted-beta IZUM runs (24h window sampled 2026-07-30) | SUCCESS 127; FAILED 152 |
| Last SUCCESS | `2026-07-30 19:14:28Z` (`last_successful_sync_at`) |
| Subsequent runs | Continuous `FAILED` with summary containing `Read timed out` |
| Stored `error_category` | `contract` (incorrect for I/O timeout; see Failure behavior) |
| Freshness thresholds | aging 300s / stale 900s on source row |
| Public masking | `MunicipalFacilityQueryService` nulls `availableSpaces` unless LIVE/AGING |
| Metrics | `parkio.municipal.sync.*` exist; no municipal Prometheus alert rules |
| Health | `MunicipalSourceHealthIndicator` always UP; details only |
| Runbook | `municipal-parking-source-runbook.md` covers enable/sync/rollback; no alert thresholds or consecutive-failure playbook |
| Naming collision | Ops WP-06.* and frontend `WP-06-PRODUCTION-HARDENING.md` are **not** this package |

## 3. Current state

### Completed foundations (do not redesign)

- Municipal source registry + sync runs (V28)
- IZUM adapter, scheduler (fixed-delay default 120s), RUNNING lock
- Occupancy freshness LIVE/AGING/STALE/UNAVAILABLE + public masking
- OSM import/publication with null availability
- IZELMAN historical inventory/tariffs (publication off)
- Canonical registry, provenance store, review/audit (V32)
- Bounded candidate generation + run audit (V33), flags default false
- Automatic linking hard-disabled
- Hosted-beta disk preflight (>=12 GiB)

### Observability gaps (this package)

- No durable consecutive-failure counter (only run history)
- Timeout failures often stored as `contract` when the exception simple name lacks `timeout`
- No Prometheus recording/alert rules for prolonged IZUM failure or stale age
- No operator SLA thresholds or recovery confirmation checklist
- Actuator details are non-blocking UP even when `izumStatus=failing|stale`

## 4. Problem statement

Municipal live availability depends on IZUM remaining fresh. When the upstream API
times out, the product correctly hides free spaces, but operators cannot reliably
detect prolonged failure, distinguish timeout from schema contract breakage, or
confirm recovery after an outage-especially across deploys that already stress disk
and restarts.

## 5. Goals

1. Classify sync failures accurately (`timeout` / `http` / `contract` / `concurrent_run`).
2. Expose consecutive failure and time-since-success as first-class ops signals.
3. Add Prometheus recording rules and alerts for IZUM failure/stale conditions.
4. Extend health details (still non-liveness-blocking) with consecutive failures and last error category.
5. Publish an IZUM outage/recovery runbook with thresholds and confirmation steps.
6. Prove masking and recovery semantics with automated tests.
7. Keep all registry and IZELMAN publication flags false by default.

## 6. Non-goals

- Enabling `reviewed-linking`, candidate generation, or provenance publication
- Automatic linking
- Fabricating OSM/IZELMAN availability
- Replacing `izmir-bbox-v1` with an administrative boundary
- IZELMAN legal/publication readiness
- Facility search ranking / spot-facility fusion
- Decision Intelligence WP-05 reopen
- Ops platform WP-06.3+ deployment automation
- Production launch
- Raising IZUM timeouts solely to hide upstream outages (tuning only with evidence)

## 7. Architecture

```
openapi.izmir.bel.tr
        |
        v
IzumMunicipalParkingAdapter --> MunicipalFacilitySyncService
                                        |
                    +-------------------+-------------------+
                    v                   v                   v
         municipal_source_sync_runs   occupancy          data_sources
                    |               snapshots        last_successful_sync_at
                    v
         MunicipalSourceMetrics (Micrometer)
                    |
                    v
         Prometheus recording rules + alerts
                    |
                    v
         MunicipalSourceHealthIndicator (details)
                    |
                    v
         Operator runbook (DATA-WP-06)
```

Availability projection remains unchanged: only LIVE/AGING publish `availableSpaces`.

## 8. Data model

**Prefer no new Flyway version** if consecutive failures can be derived from
`municipal_source_sync_runs` ordered by `started_at`.

Optional denormalization (only if query cost or health probe latency requires it):

| Column (optional V34) | Purpose |
|-----------------------|---------|
| `consecutive_failures` | Reset to 0 on SUCCESS/PARTIAL_SUCCESS |
| `last_error_category` | Latest completed failure category |
| `last_error_at` | Timestamp of latest failure |

If V34 is avoided, document SQL/JDBC derivation used by health and metrics gauges.

No changes to occupancy, candidates, aliases, or tariff tables.

## 9. API contracts

### Public

No DTO shape changes. Existing freshness masking remains authoritative.

### Admin / ops

- Existing manual sync endpoint unchanged.
- Optional read-only admin source-health summary only if reusable by runbook
  automation; otherwise actuator + Prometheus suffice.
- No candidate generate/review route changes.

## 10. Feature flags

| Flag | DATA-WP-06 change |
|------|-------------------|
| All registry flags | Unchanged; remain default **false** |
| IZUM enabled/scheduler | Unchanged (already true on hosted-beta) |
| IZELMAN / OSM import gates | Unchanged; remain **false** |
| Automatic linking | Remains hard-disabled |

No new product feature flag required for metrics/alerts. Alert rule enablement is
ops configuration, not an application product switch.

## 11. Scheduling/locking

- Keep existing `@Scheduled` IZUM job and one-RUNNING-per-source lock.
- Do not add ShedLock unless multi-replica parking-service is an accepted later gate.
- Do not add a candidate-generation scheduler.

## 12. Freshness/trust semantics

Preserve:

- aging_after_seconds / stale_after_seconds on source row
- LIVE/AGING -> publish spaces; STALE/UNAVAILABLE/INVALID -> null spaces
- IZUM-only municipal live availability
- OSM/IZELMAN no-availability semantics

DATA-WP-06 documents and tests these semantics; it does not loosen them.

## 13. Metrics

Extend / correct:

| Metric | Notes |
|--------|-------|
| `parkio.municipal.sync.runs` | Ensure `error_category=timeout` for timed-out I/O |
| `parkio.municipal.sync.duration` | Unchanged |
| `parkio.municipal.source.consecutive_failures` (gauge) | Per `source_key` |
| `parkio.municipal.source.seconds_since_success` (gauge) | Per `source_key` |
| `parkio.municipal.source.freshness_state` (gauge/info) | healthy/aging/stale/failing |

Prometheus:

- Recording rules under `docker/prometheus/` for IZUM failure rate and stale age
- Alert rules for prolonged consecutive failures and stale beyond threshold
  (exact burn thresholds set in implementation with hosted-beta calibration)

## 14. Health and alerting

- Keep `MunicipalSourceHealthIndicator` **non-liveness-blocking** (always UP).
- Enrich details: `izumStatus`, `consecutiveFailures`, `lastErrorCategory`,
  `secondsSinceSuccess`, `latestRunStatus`.
- Alerting via Prometheus/Alertmanager-not by failing liveness.
- Document how to read `/actuator/health` details in the runbook.

## 15. Security/privacy

- Do not log raw upstream payloads or secrets.
- Truncate `error_summary` (existing <=1024).
- Alerts must not include tokens, JWT, or full response bodies.
- Evidence directories outside Git; no official datasets committed.

## 16. Failure behavior

Correct `MunicipalFacilitySyncService.category(Throwable)`:

1. Walk cause chain and message for timeout / timed out / SocketTimeout -> `timeout`
2. HTTP status client failures -> `http`
3. Schema/fingerprint/parse contract -> `contract`
4. Concurrent RUNNING -> `concurrent_run` (existing skip path)

On timeout:

- Mark run FAILED with `timeout`
- Do **not** clear `last_successful_sync_at`
- Occupancy ages naturally; public spaces disappear after stale threshold
- Increment consecutive-failure gauge

On SUCCESS/PARTIAL_SUCCESS:

- Reset consecutive failures
- Update `last_successful_sync_at`
- Emit recovery-friendly metric transition for alert hysteresis

## 17. Migration

- **Default:** no migration (derive from sync runs).
- **Optional V34:** only if denormalized consecutive-failure columns are required
  for health probe SLOs. Must be additive, non-destructive, seed no sync data.

## 18. Testing

| Layer | Coverage |
|-------|----------|
| Unit | Failure category classification (timeout vs contract vs http) |
| Unit | Consecutive failure increment/reset |
| Unit | Public masking unchanged for STALE |
| Integration (Postgres) | Sync run history derivation; last success retention on failure |
| Metrics | Gauge updates on fail/success sequences |
| Architecture/docs | Prometheus rule files parse; runbook linked from ops index |
| Regression | IZUM/OSM/IZELMAN publication flags; registry flags false; no candidate apply |

No external network in CI. Use fixtures for timeout exceptions.

## 19. Hosted-beta rollout

### DATA-WP-06 (implementation)

- Code + tests + Prometheus rules + runbook
- Deploy dark (no product flag flips)
- Do not claim recovery of upstream IZUM

### DATA-WP-06A (hosted-beta gate - separate)

1. Deploy implementation commit
2. Capture pre-state: consecutive failures, last success, public freshness
3. Confirm alerts fire (or would fire) under current failure conditions **without**
   paging production
4. When upstream recovers (or fixture/manual success), confirm gauges reset and
   public LIVE/AGING returns for fresh snapshots
5. Confirm no registry/IZELMAN flag changes
6. Restore least privilege / alert routing as designed
7. Evidence outside Git

## 20. Rollback

1. Revert parking-service image / disable new alert rules
2. Application remains safe: masking and scheduler already exist
3. Optional V34 columns are forward-compatible; do not drop in emergency
4. Registry and IZELMAN flags remain false

## 21. Risks

| Risk | Mitigation |
|------|------------|
| Upstream remains down | Alerts + runbook; do not fabricate availability |
| Timeout mis-tune hides real slowness | Evidence-based timeout changes only in 06A |
| Alert noise | Hysteresis on consecutive failures + stale age |
| Scope creep into reviewed-linking | Explicit non-goal |
| Confusion with ops WP-06 | `DATA-WP-06` / `wp-data-06-*` naming |

## 22. Acceptance criteria

### DATA-WP-06

1. Timeout failures classify as `timeout`, not `contract`.
2. Consecutive failures and seconds-since-success are observable (gauge and/or health detail).
3. Prometheus recording + alert rules exist for IZUM prolonged failure/stale.
4. Runbook documents thresholds, diagnosis, recovery confirmation, and kill switches.
5. Public STALE masking tests still pass.
6. Registry/IZELMAN flags remain default false; automatic linking remains disabled.
7. No candidate apply; no source-link movement from this package.
8. Unit + Postgres tests cover taxonomy and consecutive-failure semantics.
9. Spec and architecture index updated; Decision WP-05 untouched.

### DATA-WP-06A

1. Deployed image includes DATA-WP-06.
2. Hosted-beta evidence shows correct category on live timeouts (if still failing).
3. Recovery confirmation path proven when a success occurs.
4. Production unchanged; no registry enablement.

## 23. Deferred work

| Item | Why deferred |
|------|--------------|
| Reviewed-linking enablement | Needs policy + candidates; zero eligible after 05A |
| Candidate regeneration scheduler | Prefer explicit ADMIN; not next risk |
| Provenance publication UX | Product/frontend; flag exists |
| `izmir-bbox-v1` -> admin polygon | Licensing/geo dependency |
| IZELMAN publication | Historical/AGING; legal gate |
| Multi-replica ShedLock | Architecture decision later |
| Source-quality marketplace / SLA product catalog | Product scope |
| Facility ranking / fusion | Explicitly out of DATA roadmap evidence |
| Operator coverage dashboard UI | Product/frontend; read-only aggregates are [DATA-WP-15](wp-data-15-engineering-specification.md) |

## 24. Package phasing

| Phase | Objective | Commit boundary | Deploy |
|-------|-----------|-----------------|--------|
| **DATA-WP-06** | Deterministic implementation + tests + rules + runbook | One or more impl commits on `api` | Not required to claim 06 complete locally; deploy only via 06A |
| **DATA-WP-06A** | Hosted-beta observability + recovery validation | Separate ops gate after 06 | Required |

## 25. Implementation status

**Specification only.** No code, migration, flag, or deployment is performed by this
document commit. Hosted-beta currently exhibits prolonged IZUM upstream timeouts;
treat that as an **operational incident** in parallel with scheduling DATA-WP-06
implementation-do not wait for the engineering package to begin incident response
using existing run history and kill switches.