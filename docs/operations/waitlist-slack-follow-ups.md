# Waitlist Slack notifications — follow-ups (outside the PR #74 release)

This document tracks deferred and follow-up work for the waitlist operational
Slack notifications. By owner decision these items are **not** part of the
PR #74 release and are **not** filed as public GitHub issues. Update the status
here when an item is picked up; each item needs its own branch and
authorisation.

| ID | Item | Status | Why deferred / trigger to revisit |
|---|---|---|---|
| WSN-F1 | **Daily digest** instead of, or in addition to, per-confirmation messages | Deferred | The initial release is one message per confirmed subscription (decision). Revisit if confirmation volume makes the channel noisy (for example > 50/day sustained). |
| WSN-F2 | **Signed Resend delivery webhooks** (Svix-signed public endpoint, persisted Resend message id), enabling a truthful *terminal email delivery failure* notification (`bounce_hard` / `complaint` / `suppressed`) | Deferred | The current integration only proves provider acceptance. It needs a new public endpoint, signature verification, a schema change and a privacy review. |
| WSN-F3 | **Resend client timeout** in the gateway waitlist sender (`WaitlistRestClientConfig` builds the `RestClient` with no explicit connect/read timeout) | Deferred, observation only | Kept out of this release by decision. It affects the submit/resend request path, not notifications. Note that PR #78 adds bounded timeouts to the *auth-service* sender, not to the gateway. |
| WSN-F4 | **Legacy registration file-inbox consumer privacy** (`scripts/slack_biz/registration_consumer.py`, PR #54): rejected envelopes are kept verbatim in `.invalid/`, an `.error.txt` with the exception text is written beside them, and warnings log the file name plus the exception message. This can include attacker-controlled key names/values. | Open, out of scope | Apply the waitlist model: bounded categories, no names or values in logs, no raw retention by default. Until then this consumer stays **OFF**: the Civo package does not install it, the installer refuses registration/Kafka settings, and the relay's trusted producers are limited to `gateway-waitlist-outbox`. |
| WSN-F5 | **Prometheus alert rules** for the new gauges/counters (`parkio_waitlist_ops_outbox_pending`, `parkio_waitlist_ops_notifications_total{outcome=~"export_deferred_.*|export_failed|record_failed"}`) and relay deferrals | Proposed | Shared `docker/prometheus/alerts.yml` plus a Prometheus reload on the host. Proposed rule text is in the ops doc under "Capacity monitoring". |
| WSN-F6 | **Gateway pin drift**: the running Civo gateway is `sha256:8b8a08ba…` (revision `83fa625b`), while `docker/docker-compose.gmp-release-pins.yml` (repo and host) pins `sha256:5c66e0fb…` | Open, deploy-blocking hygiene | Any `up -d gateway-service` from the current pins would downgrade the gateway. The release pin PR for PR #74 must set the new candidate digest, and the rollback instructions name `8b8a08ba`, not `5c66e0fb`. |
| WSN-F7 | **Host checkout drift** on `parkio-civo-prod`: `/opt/parkio` is at `bf9cad51` with local modifications and untracked pin/backup files | Open, operator | Reconcile to the release SHA before installing the relay package, without losing host-local pin backups. |
