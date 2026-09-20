# Fault-injection results — PARKIO-Y03A

**Command:** `python scripts/slack_biz/run_reliability_acceptance.py`  
**Mock Slack only. Real Slack NOT_EXECUTED.**

| # | Scenario | Result |
|---|----------|--------|
| 1 | Registration commit vs rollback (file inbox + envelope) | PASS |
| 2 | Crash after durable enqueue before ack | PASS |
| 3 | Queue write failure before ack | PASS |
| 4 | Second worker rejected / single-worker | PASS |
| 5 | Crash after claim → lease reclaim | PASS |
| 6 | Accept then drop connection → ambiguous retry (not delivered) | PASS |
| 7 | Reject/exhaust → dead not success | PASS |
| 8 | Restart pending/unknown/dead + operator resolve | PASS |
| 9 | Replay inside retention | PASS |
| 10 | Replay outside retention → new admission (dup risk) | PASS |
| 11 | Disabled → no outbound; offset_reset=latest | PASS |
| 12 | Sensitive / mention safety | PASS |
| 13 | Ambiguous exhaust → `delivery_unknown` | PASS |
| 14 | Live Kafka consumer | **NOT_EXECUTED** (ABSENT) |

**Summary:** PASS=13 FAIL=0 NOT_EXECUTED=1  

Also revalidated Y03 suite: `run_acceptance.py` → **15/15 PASS**.

## Scope classification

| Layer | Result |
|-------|--------|
| Delivery-core source acceptance | PASS (ambiguous fix + states) |
| Registration adapter acceptance | File-inbox PASS; Kafka live NOT_EXECUTED |
| Isolated E2E (envelope→consumer→queue→worker→mock) | PASS (file path) |
| Full auth-service TX E2E | NOT_EXECUTED (no disposable full stack in this package) |
| External activation readiness | NOT ready (disabled; no real webhook exercise) |
