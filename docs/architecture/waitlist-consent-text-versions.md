# Waitlist consent text versions (CL-F18)

The gateway records, for every waitlist subscription, which consent text the subscriber accepted.
This document is the register of those texts. Registering a text here is not legal approval of its
wording, and nothing here decides what may be sent to whom; those decisions stay with the owner.

## How a consent is recorded

- The marketing form sends `consent: true` and `consentTextVersion` (the id below) with the
  submission. The gateway accepts only ids registered here.
- `waitlist_interest.consent_text_version` stores the id; `consent_timestamp` is the gateway's
  receipt time of the consented submission (the authoritative consent time);
  `client_consent_timestamp` keeps the client's skew-checked assertion.
- `parkio.waitlist.consent-required` (default `true`) refuses a submission without `consent=true`
  and a registered version (`400 WAITLIST_CONSENT_REQUIRED` / `400 WAITLIST_CONSENT_VERSION_INVALID`).
  An explicit `consent=false` is refused in every mode. `false` is a compatibility mode for the
  deploy window in which a gateway that records versions runs in front of a marketing site that
  does not send them yet; such submissions are stored as `unversioned-client`.
- The CSV export (`/api/v1/waitlist/export`) and the admin list carry `consentTextVersion`,
  `consentTimestamp` and `confirmedAt`, so the stored version and times are retrievable.
- A resubmission for an e-mail that is already PENDING does not change the stored row: the first
  consent evidence (version and time) stays; the gateway only resends the confirmation e-mail.

## Registered versions

| Version | Locale | Text (verbatim, the marketing checkbox label) | SHA-256 |
|---|---|---|---|
| `waitlist-consent-v1` | tr | Kayıtlar açıldığında Parkio’nun beni e-posta ile bilgilendirmesini kabul ediyorum. Onay için e-postamdaki bağlantıyı kullanacağım. | `20f3d2355fd83a52b6753c1a86fbfe8afc929e3e916cb0b34e9add4d6294824c` |
| `waitlist-consent-v1` | en | I agree that Parkio may email me when registrations open. I will confirm via the link in my email. | `eed8440ccbfa389bd22e915b39ce5de1fbf27ef01b9158368c7fa8b0e2fd0a75` |

The same texts and hashes are in `WaitlistConsentText` (gateway) and are pinned against
`web/marketing/i18n.js` by `web/marketing/waitlist.consent-version.test.mjs`, so a wording change
without a new version fails the marketing validation and the gateway tests. A new wording gets a new
id (`waitlist-consent-v2`, ...), a new row here, and the marketing form switches to it; old rows keep
their id.

## Sentinel values

| Value | Meaning |
|---|---|
| `legacy-unversioned` | Rows recorded before this change (migration V6 backfill). They completed double opt-in for the same purpose, but which wording they saw is not recorded. |
| `unversioned-client` | A compatibility-mode submission (`consent-required=false`) that carried no version. |

Neither sentinel is accepted from a client.

## Open decisions (owner; not granted by this change)

- Legal confirmation of the v1 wording. Recording it under an id does not approve it.
- What legacy-unversioned subscribers may receive, and whether they must re-consent under a
  registered version before any message. No message to them is authorized by this change.
- Whether `unversioned-client` rows need a follow-up once the compatibility window is closed.
