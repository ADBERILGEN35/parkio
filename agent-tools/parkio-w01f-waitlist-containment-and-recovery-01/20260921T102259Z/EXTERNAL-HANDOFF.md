# External activation (unresolved only — W01F)

Source containment + isolated recovery artifact work is in the draft PR.
Publication still blocked on operator inputs below.

## Technical inputs still required
| Item | Status |
|---|---|
| Resend account / domain verification | Not confirmed |
| Sender / reply-to | Confirm `PARKIO_WAITLIST_EMAIL_FROM`, `PARKIO_WAITLIST_EMAIL_REPLY_TO` |
| Provider-issued DNS (SPF/DKIM/etc.) | Await provider dashboard — do not invent |
| Secrets (names only) | `PARKIO_WAITLIST_RESEND_API_KEY`, `PARKIO_WAITLIST_HASH_SECRET`, gateway internal secret — vault install only |
| `PARKIO_WAITLIST_ALLOW_LOGGING_PROVIDER=false` | Required for live email |
| `PARKIO_WAITLIST_ADMISSIONS_ENABLED` | Keep `false` until authorized cutover; restart to change |
| Authorized live test email address | Required before admissions=true |
| Hostinger access | Required for `web/marketing/` upload after API/email live |

## Artifact publication / gateway-only deploy plan (not executed)
1. Merge focused containment/recovery PR into `api` when accepted.
2. Publish gateway image digest from accepted head (no media/parking pin changes).
3. Record a second V2-compatible recovery digest of the same schema generation (may be the same build kept as recovery pin).
4. Deploy gateway with Flyway V2, CORS, Resend, admissions **false**.
5. Authorized live email test → admissions **true** + restart → Hostinger upload.

## Authorizations still required
Gateway registry publish + pin update; Civo deploy/migration; DNS; secret install; live email; Hostinger; admissions enable.
