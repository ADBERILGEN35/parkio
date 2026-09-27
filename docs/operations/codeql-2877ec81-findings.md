# Unresolved CodeQL findings (api 2877ec81)

Record only. Not a fix, dismissal, or suppression. Not part of the
recovery persist slice.

Source check: GHAS **CodeQL** `108696518916` on
`2877ec81df17d76311125592dfcf66c4b8f6d324` (PR #44 head after #109).
Security CI CodeQL *jobs* succeeded; this is a findings gate.

## 1. Marketing URL includes — classification not closed

Annotations: `scripts/validate-marketing-site.mjs` lines 155, 159, 164.
Rule: `js/incomplete-url-substring-sanitization`.

The lines are `Array.includes` of exact JSON-LD strings parsed from our
own HTML. That *looks* like a constant-equality check, but CodeQL's
query is about substring sanitization of URL values.

Required before any false-positive claim:

- Data-flow: is the receiver a URL being tested as a substring, or an
  array of exact strings from first-party JSON-LD?
- Source: who constructs `founderSameAs` / `orgSameAs` (parser of our
  committed HTML vs any external input)?
- Confirm the query’s sanitizer model against `Array.prototype.includes`
  versus `String.prototype.includes`.

Do not close or dismiss the alert until that evidence is written against
the query and the source.

## 2. CSRF disable — authentication-path review not closed

Annotation: `SecurityConfig.java:27`.
Rule: `java/spring-disabled-csrf-protection`.
Open on `master` as [alert 7](https://github.com/ADBERILGEN35/parkio/security/code-scanning/7).

“Stateless JWT” and “pre-existing” are not enough. Required:

- Inventory every auth path: bearer JWT, cookies, sessions, browser
  form posts, refresh-token cookies if any, gateway-browser flows.
- State whether any cookie or browser-supplied credential can change
  auth state without a CSRF token.
- If a cookie-authenticated path exists, CSRF disable is a defect on
  that path regardless of JWT defaulting.

Do not mix a CSRF change into recovery work.