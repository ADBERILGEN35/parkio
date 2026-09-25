# WEB-MUNI-08 — Auth redirect query preservation

**Program:** WEB-MUNI  
**Package:** WEB-MUNI-08  
**Status:** Implementation complete — **WEB-MUNI-08A not started**  
**Scope:** Frontend/web only. No backend auth contract or deploy change.

## Goal

Preserve safe `/map` query state across the existing protected-route login redirect
flow so shared municipal discovery URLs survive authentication without weakening the
current redirect safety model.

## Canonical redirect contract

Allowed redirect target:

- same-origin internal pathname
- route already recognized by the frozen route manifest
- route already marked redirect-eligible
- safe query string preserved as-is
- hash fragment stripped

Rejected redirect target:

- absolute external URL
- protocol-relative URL
- unknown internal route
- malformed target
- encoded external redirect
- `javascript:`, `data:`, and `file:` scheme payloads
- path traversal or backslash/path-confusion variants
- control-character payloads

Output contract:

- normalized internal pathname
- preserved safe `search`
- no fragment
- no double decoding
- no redirect-eligibility expansion

## Auth flow

1. Anonymous user opens a protected internal URL.
2. `RoutePolicyBoundary` sanitizes the current pathname + search.
3. Login redirect uses `/login?return=<sanitized-internal-target>`.
4. After successful login, `LoginPage` sanitizes the `return` query value again.
5. Navigation returns to the same safe internal route with query preserved.
6. The destination page owns any semantic URL canonicalization.

Legacy `location.state.from` support remains as a compatibility fallback when the
login `return` query is absent.

## Query preservation

WEB-MUNI-08 preserves safe query strings without interpreting municipal semantics.
That means:

- WEB-MUNI-07 managed map params survive auth redirect round-trips
- `smartReturn=1` survives
- unrelated safe query params survive
- duplicate and blank query params remain destination-owned

`MapPage` still owns municipal canonicalization, including flag-off stripping and
invalid/stale param repair.

## Security notes

- Login cannot be used as an open-redirect trampoline.
- Hash fragments are always stripped and never restored.
- Route eligibility remains owned by `route-manifest` and `isRedirectEligiblePath()`.
- Redirect logic does not add local/session storage fallbacks.

## Feature-flag behavior

The auth redirect layer is municipal-feature agnostic. With
`VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false`, safe query state is still carried
through login, then `/map` applies its existing community-only canonicalization.

## Rollback

1. Revert the WEB-MUNI-08 commit, or
2. Restore the prior frontend build artifact

No backend, auth API, DTO, database, or migration rollback is required.

## WEB-MUNI-08A

Hosted-beta validation remains a separate gate. It should prove:

- anonymous `/map?...` deep-links survive login
- `smartReturn=1` coexists with municipal URL state
- fragments remain stripped
- unsafe redirect targets still fall back to the canonical safe route
- no auth/history/request loop is introduced
