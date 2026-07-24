# Sprint 2.4 WP-04 Frontend Data Architecture

## 1. Scope and non-goals

WP-04 establishes one coherent Web server-state architecture on TanStack Query:

- QueryClient ownership and policy
- Canonical query keys
- SDK access boundaries
- Authentication-linked cache lifecycle
- Mutation/cache synchronization
- Async UI-state contracts
- Guardrails, focused tests, and CI

**Non-goals:** backend redesign, Mobile/Mobile-v2 migration, WP-03 routing changes,
offline-first persistence, repository-wide UI redesign, WP-05 parking flows.

WP-03 remains frozen. This document does not declare formal WP-04 closure.

## 2. Canonical ownership

| Concern | Owner |
|---------|--------|
| QueryClient construction | `apps/web/src/providers/query-client.ts` (`createWebQueryClient`) |
| Runtime composition | `apps/web/src/app/runtime.ts` |
| QueryClientProvider mount | `apps/web/src/providers/QueryProvider.tsx` via `App.tsx` |
| Parkio SDK construction | `apps/web/src/app/sdk.ts` |
| SDK injection | `useParkioSdk()` / `AppRuntimeContext` |
| Query key factories | `apps/web/src/data/keys.ts` |
| Session cache clear | `apps/web/src/data/sessionQueryCache.ts` |
| Identity → cache bridge | `apps/web/src/data/SessionQueryCacheSync.tsx` |
| Me / parking query options | `apps/web/src/data/query-options/*` |
| Domain hooks | `apps/web/src/data/hooks/*` |
| Route selection | WP-03 (`RoutePolicyBoundary` / AuthBootstrap) — unchanged |

## 3. Runtime and QueryClient composition

```
main.tsx
  └─ createWebAppRuntime()
       ├─ createWebQueryClient()   (or injected test client)
       ├─ createParkioSdk()
       ├─ createAuthSession()
       └─ createAppRouter()        (WP-03)
  └─ <App runtime={runtime} />
       └─ QueryProvider(client)
            ├─ SessionQueryCacheSync
            ├─ AuthBootstrap
            └─ RouterProvider
```

Exactly one production QueryClient is created per runtime. Components must not
construct `QueryClient` or SDK clients.

## 4. QueryClient global policy

Exported as `WEB_QUERY_CLIENT_POLICY` / `shouldRetryQuery`:

| Option | Value |
|--------|--------|
| `queries.staleTime` | `30_000` |
| `queries.gcTime` | `300_000` |
| `queries.refetchOnWindowFocus` | `true` |
| `queries.refetchOnReconnect` | `true` |
| `queries.refetchOnMount` | `true` |
| `queries.networkMode` | `online` |
| `mutations.retry` | `0` |
| `queries.retry` | typed: no retry for Unauthorized / Forbidden / Validation / AccountNotActive; RateLimit / UserStatusUnavailable `< 2`; other 4xx `false`; else `< 1` |

## 5. Domain-specific overrides

| Domain | Override | Reason |
|--------|----------|--------|
| Locale preferences bootstrap | `staleTime: 60_000` | infrequent |
| Map vehicle | `staleTime: 5 * 60_000` | rare change |
| Nearby spots | `placeholderData: keepPreviousData`, `staleTime: 30_000` | keep markers during re-search |
| Spot media URL | `staleTime: 0`, `gcTime: 0` | signed URLs expire |

## 6. Query key conventions

- Domain root → resource → identity / normalized filters
- Serializable primitives only; normalize nearby filters via `normalizeNearbyFilters`
- One factory family per domain in `data/keys.ts`
- Prefer narrow invalidation (`parkingKeys.mySpots()`) over whole-client clears

## 7. SDK access boundary

- Features call `useParkioSdk()` or domain hooks that consume the injected SDK
- No `fetch`/`axios` for Parkio backend domain calls in pages/components
- No bearer-header construction in features
- Approved exception: non-server-state browser APIs; geocoding debounce remains local UI state over SDK geocoding

## 8. Query/mutation definition ownership

- Prefer `queryOptions` in `data/query-options` for reusable me/parking reads
- Domain hooks wrap options for pages
- Mutations remain colocated with forms when they own UX toasts; they must write
  cache through canonical keys (`setQueryData` / targeted `invalidateQueries`)

## 9–12. Authentication and cache lifecycle

`SessionQueryCacheSync` subscribes to `authStore.subscribeIdentityChanges`.

When `previous.userId !== current.userId` (login switch, logout → anonymous,
anonymous → user):

1. `cancelQueries` for each `USER_SESSION_QUERY_ROOTS` entry
2. `removeQueries` for the same roots

**Cleared:** `me.*`, gamification personal keys, notifications, reports,
moderation, analytics, admin, `parking.my-spots`.

**Preserved:** public nearby discovery (`parking.nearby`).

Invalid session / refresh failure still tears down auth via `AuthSession`
(WP-03 routing authority). Cache clear follows the resulting identity change —
Query error handlers must not redirect.

## 13. Mutation/cache matrix (critical flows)

| Mutation | Cache | Strategy | Reason |
|----------|-------|----------|--------|
| Update profile | `meKeys.profile()` | `setQueryData` | response is canonical entity |
| Upsert vehicle | `meKeys.vehicle()` | `setQueryData` | response is canonical entity |
| Update preferences | `meKeys.preferences()` | `setQueryData` | response is canonical entity |
| Smart Return plan/settings/cancel | `meKeys.smartReturn()` | `setQueryData` | response is canonical entity |
| Create spot (upload) | my-spots + nearby | `invalidateQueries` | list membership changes |
| Verify / claim spot | spot + nearby patch + my-spots | `setQueryData` + invalidate my-spots + gamification invalidate | entity + derived aggregates |
| Report spot | `reportsKeys` | invalidate | list membership |
| Gamification-affecting actions | stats/points/level/… | `invalidateGamificationQueries` | async Kafka-derived state |

Optimistic updates are not the default for WP-04.

## 14. Async UI-state contract

| State | Rule |
|-------|------|
| Initial loading | Show loading UI when no data yet (`isPending` && !data) |
| Background refetch | Keep showing prior data (`isFetching` with data); Map nearby uses `placeholderData` |
| Empty success | Use empty-state UI; not an error |
| Recoverable error | `FriendlyApiErrorMessage` / mapped toast — no raw stack traces |
| Auth/session failure | Auth session teardown + WP-03 redirects; no Query-owned redirect |
| Mutation pending | Disable submit; preserve form values |
| Mutation failure | Toast / inline error; do not leave incorrect optimistic state (optimism unused by default) |

## 15. Approved exceptions

- Admin / moderation / analytics pages may call SDK via `useQuery` with canonical keys without dedicated hooks yet
- Locale bootstrap uses a dedicated preferences key suffix to avoid colliding with the preferences form cache
- Public nearby cache survives logout by design
- Test harnesses may construct `QueryClient` / memory routers

## 16–18. Guardrails, tests, CI

- Production: `pnpm guardrails` includes WP-04 AST checks (`wp04-data-architecture.mjs`)
- Fixtures: `pnpm guardrails:test` (WP-04 cases included)
- Focused: `pnpm --filter @parkio/web test:wp04` and `pnpm guardrails:wp04`
- Frontend CI runs the focused WP-04 Vitest gate with `--retry=0` semantics via `vitest run`

## 19. Remaining migration debt

- Dedicated hooks for admin, moderation, analytics, gamification, notifications
- `mutationOptions` factories for profile/smart-return/spot mutations
- Align mobile `sessionQueryCache` imports with shared package (out of WP-04 Web scope)
- AbortSignal support on users API reads (parking nearby already supports it)
  — **addressed in WP-06** for canonical Web query-options + typed SDK reads

## 20. Rules for future contributors

1. Add keys only in `data/keys.ts` (or a domain file re-exported from it).
2. Do not create a second QueryClient or SDK in features.
3. Clear user cache only through `clearUserSessionQueries`.
4. Prefer `setQueryData` when the mutation response is the full entity; otherwise invalidate narrowly.
5. Do not redirect from Query `onError` — leave routing to WP-03.
6. Extend WP-04 guardrail fixtures when adding a new forbidden pattern.
