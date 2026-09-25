# PR #84 operator acceptance — defects (not fully accepted)

Date: 2026-09-23. No production mutation, replay, Slack/email send, merge, or deploy in this step.

## Proven missing-name root cause

First drop is **gateway export**, not Slack filtering.

Sanitized correlation of the operator-confirmed named row:

| Stage | Evidence |
|---|---|
| Stored subscriber | CONFIRMED named=true; created 2026-09-23T07:24:15Z; confirmed 07:24:35Z |
| Outbox | EXPORTED; interest_id present; interest_has_name=true; exported 07:24:44Z |
| Frozen v2 inbox envelope | contractVersion=2; fullName **null**; counts present (int/int/str) |
| Queue row | contract 2; ctx.full_name **null**; body_name=fallback; counts present |
| Renderer | Deployed templates.py still reads ctx.full_name and prints Ad belirtilmemiş |

Gateway log at export (07:24:44Z):

Waitlist ops fullName load failed; category=BadSqlGrammarException

Cause: JdbcWaitlistInterestRepository.findById bound id.toString() into WHERE id = ?. PostgreSQL rejects uuid = character varying. The exporter catches that RuntimeException and continues with ullName=null. Admin /admin/waitlist uses a different query, so the name is visible there.

Not the cause: v1 contract (this event was v2), sensitive-key strip on the waitlist consume path (not called), allow-list rejection (envelope accepted), confirm SQL (does not clear full_name).

The already-delivered envelope is frozen with ullName: null. Do not rewrite or replay it.

## Alarm screenshot vs live template

Alertmanager v0.27.0, uptime 2026-09-23T07:02:29Z (Turkish template activation).
Loaded config: Turkish titles (Kritik, Sorun çözüldü); no [FIRING], no .Status | toUpper.
MunicipalSourceSecondsSinceSuccessCritical startsAt **2026-09-22T16:49:47Z** (BEFORE reload).

The English FIRING screenshot is a **historical Slack message** from before template activation, not proof the current loaded template is old.

Remaining live presentation gap: the loaded Turkish wrapper still titles Kritik: MunicipalSourceSecondsSinceSuccessCritical and can surface raw annotations. The proposed render-config.sh maps this alertname to the IZUM human structure. Routing, grouping, inhibition, and Slack/webhook separation are unchanged. Alert expressions/thresholds are unchanged.

## Corrected message previews

See waitlist_v2_named.txt and lertmanager-preview-critical.txt.

## Release scope (after review)

Requires deploy:

1. **gateway-service** image — UUID bind fix (this is the name bug).
2. **slack_biz host files** — templates/adapters/safety (readable TR body). Restart consume/worker units only if they already load from /opt/parkio; no queue replay.
3. **Alertmanager render-config.sh** + container restart (presentation only). parkio-prod-compose.sh cannot recreate AM under the azure-hosted-beta profile; use validated mtool check-config then docker restart parkio-alertmanager.

Does not require: marketing/Hostinger, web, auth, parking, New Relic, waitlist flag changes, event replay.

Keep: registration CLOSED, invite creation false, required-name=true, Slack on, NR on, waitlist recording on.

Rollback: revert gateway image pin; restore previous slack_biz files and render-config.sh; restart those units only.

## Tests

- W20/W21/W24 waitlist acceptance (24 PASS)
- findById UUID bind unit test
- PostgresIT named export (needs Docker)
- scripts/test_alertmanager_presentation.py
