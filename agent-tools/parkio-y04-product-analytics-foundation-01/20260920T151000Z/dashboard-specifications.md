# Y04 — Dashboard specifications (UNEXECUTED against vendor)

No PostHog project is provisioned. Specs only — **do not invent screenshots**.

## 1. Map → preview → detail funnel

Events: `map_ready` → `facility_preview_opened` → `facility_detail_opened` → `returned_to_map`  
Break by `platform` (`web` | `mobile_v2`). Exclude opted-out (absence ≠ zero usage).

## 2. Screen active-time distribution

Event: `screen_engagement_summary`  
Metric: `activeDurationMs` by `screenName`; filter `incomplete=false` for precise charts; report incomplete share separately.

## 3. Map init failure / retry

Events: `map_init_failed`, `retry_attempted`, `retry_outcome`  
Breakdown: `mapErrorCode`, `retryOutcome`.

## 4. Returning sessions (identity-scoped)

Within anonymous `analyticsSessionId` or approved `distinctId` only.  
**Do not** claim authenticated return-visit cohorts until backend pseudonym is live.

## 5. Pseudonymous timeline (ops)

Access-controlled; sensitive. Requires vendor project + RBAC. **UNEXECUTED**.

## Interpretation rules

- Missing / opted-out telemetry ≠ zero usage  
- No historical analytics before instrumentation landed  
- Separate **session** metrics from **user** metrics
