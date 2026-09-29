# Isolated acceptance — PARKIO-Y03

**Runner:** `python scripts/slack_biz/run_acceptance.py`  
**Mock:** local `HTTPServer` webhook catcher  
**Real Slack hosts:** refused  
**Inherited prod webhook:** overridden / not used for delivery  

## Results (20260920T140337Z)

| # | Scenario | Result |
|---|----------|--------|
| 1 | Successful registration commit → intended event | **PASS** |
| 2 | Rolled-back/failed registration → no completion | **PASS** |
| 3 | Duplicate/replayed events follow dedup | **PASS** |
| 4 | Concurrent consumers do not bypass dedup | **PASS** |
| 5 | Crash/restart preserves pending work | **PASS** |
| 6 | HTTP 429 respects Retry-After | **PASS** |
| 7 | Transient failures retry within bounds | **PASS** |
| 8 | Permanent + exhausted retries → DLT visible | **PASS** |
| 9 | Transport rejection not recorded as success | **PASS** |
| 10 | Incident/recovery correlation | **PASS** |
| 11 | Local vs offsite backup distinct | **PASS** |
| 12 | Sensitive-data / mention-injection safety | **PASS** |
| 13 | Disabled integration → no outbound | **PASS** |
| 14 | Slack outage does not fail registration path | **PASS** |
| 15 | Alertmanager config remains compatible | **PASS** |

**Summary:** `PASS=15 FAIL=0 NOT_EXECUTED=0`  
Machine JSON: `acceptance-results.json`

## Distinctions

| Layer | Status |
|-------|--------|
| Implemented behavior | Code in `scripts/slack_biz/` + backup hook |
| Mock acceptance | **15/15 PASS** |
| Real Slack delivery | **NOT_EXECUTED** |
| Production activation | **NOT done** (disabled by default) |

## Limits

- Kafka live consumer for `UserRegistered` not shipped in this slice (enqueue adapter validated against envelope contract).
- Incident **production** producer not wired (synthetic/adapter only).
- Webhook has no true thread update.
