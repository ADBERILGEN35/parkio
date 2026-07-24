# Sprint 3 WP-06 Production Hardening

## 1. Scope and non-goals

WP-06 hardens production resilience of the Web client and shared API client
**without** redesigning frozen WP-03 routing, WP-04 data architecture, or WP-05
parking flows.

**In scope:** AbortSignal propagation, cancel/timeout/network error mapping,
retry correctness, upload abort-on-unmount, focused guardrails/CI.

**Non-goals:** UX redesign, new business rules, backend contract changes,
Mobile package migration, formal WP-06 closure.

This document does not declare formal WP-06 closure.

## 2. Production risks addressed

| Risk | Before | After |
|------|--------|-------|
| Query unmount / superseded reads keep network work alive | Only nearby parking forwarded `AbortSignal` | Canonical me / parking / reports query-options forward `signal`; SDK reads accept `{ signal }` |
| Axios cancel classified as 5xx `ServerError` and retried | `getAxiosParkioError` used `status ?? 500` | Cancel → `CancellationError`; timeout → `TimeoutError`; offline → `NetworkError` |
| Query retry treated cancels as transient failures | `shouldRetryQuery` had no cancel guard | `CancellationError` never retries |
| Upload media continued after wizard unmount | Imperative upload had no abort | Upload uses `AbortController`; unmount aborts; cancel is silent |

## 3. Ownership (unchanged)

| Concern | Owner |
|---------|-------|
| QueryClient / keys / session cache | WP-04 |
| Parking mutation + spot cache helpers | WP-05 |
| Routing / redirects | WP-03 |
| Abort-capable read APIs | `@parkio/api-client` |
| Canonical query-options signal wiring | `apps/web/src/data/query-options/*` |
| Retry policy | `apps/web/src/providers/query-client.ts` |

No competing QueryClient, key factory, SDK, or mutation ownership was added.

## 4. AbortSignal contract

- Prefer `RequestOptions = { signal?: AbortSignal }` on zero-arg / id-arg reads so
  bare TanStack `queryFn: api.method` pass-throughs remain safe (context already
  exposes `.signal`).
- Nearby parking keeps the established `(params, signal?)` signature.
- Media upload already accepted `options.signal`; UploadPage now supplies one.

## 5. Guardrails and CI

- Module: `scripts/architecture/wp06-production-hardening.mjs`
- Focused: `pnpm guardrails:wp06`, `pnpm --filter @parkio/web test:wp06` (`--retry=0`)
- Production `pnpm guardrails` includes WP-06 via `check-boundaries.mjs`
- CI job: “WP-06 production hardening”

Rules:

- `wp06-query-options-abort-signal`
- `wp06-no-retry-on-cancellation`

## 6. Tradeoffs

- Admin / analytics / moderation staff reads still use page-local `useQuery`
  wrappers; many already call `() => api.method(...)` and therefore do not yet
  cancel. Hardening focused on canonical data-layer owners first.
- Nearby parking retains positional `signal` for backward compatibility with
  existing callers and tests.
- Upload cancel on unmount is silent by design (not a user-facing error).

## 7. Remaining production debt

- Forward `signal` from remaining page-local admin/analytics/moderation queries
- Optional dedicated hooks for gamification/notifications (WP-04 debt)
- Shared mobile parking/query abort package alignment (out of Web WP-06 scope)
- Appeal mutation factories (WP-05 debt)

## 8. Rules for future contributors

1. New canonical `query-options` `queryFn`s must forward `signal`.
2. Do not map Axios cancels to HTTP 5xx — use `CancellationError`.
3. Never retry `CancellationError` in Query defaults.
4. Do not redesign WP-03 / WP-04 / WP-05 ownership to “fix” resilience.
