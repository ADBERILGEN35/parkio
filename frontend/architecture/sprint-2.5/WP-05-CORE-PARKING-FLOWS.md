# Sprint 2.5 WP-05 Core Parking Flows

## 1. Scope and non-goals

WP-05 establishes the canonical **Web** parking lifecycle on top of frozen WP-03
routing and WP-04 data architecture:

- Nearby discovery
- Spot detail (fetch / refresh / ownership-aware actions)
- Spot creation (media upload + create)
- Verify / claim / report
- My spots
- Smart Return (settings + today plan)

**Non-goals:** backend redesign, Mobile/v2 migration, WP-03 route changes,
QueryClient / key-factory redesign, formal WP-05 closure.

WP-03 and WP-04 remain frozen. This document does not declare formal WP-05 closure.

## 2. Workflow ownership

| Workflow | Route / surface | Read owner | Mutation owner |
|----------|-----------------|------------|----------------|
| Nearby | `/map` (`MapPage`) | `useNearbySpotsQuery` | — (filters are UI state) |
| Spot detail | `/spots/:spotId` | `useSpotDetailQuery` + media URL hook | `useVerifySpotMutation` / `useClaimSpotMutation` / `useReportSpotMutation` |
| Create | `/upload` | — | media upload (SDK) + `useCreateSpotMutation` |
| My spots | `/my-spots` | `useMySpotsQuery` | — |
| Reports list | `/reports` | `useMyReportsQuery` | appeals remain page-local moderation API |
| Smart Return | Profile card + map `?smartReturn=1` | `useMySmartReturnQuery` | `useSmartReturn*Mutation` / `useUpdateSmartReturnSettingsMutation` |

## 3. Cache ownership

| Key / helper | Owner |
|--------------|-------|
| `parkingKeys.*` | `apps/web/src/data/keys.ts` (WP-04) |
| `applyParkingSpotUpdate` | `apps/web/src/data/parking/spotCache.ts` |
| `syncAfterSpotCreate` | same |
| `syncAfterSpotLifecycleMutation` | same |
| `applySmartReturnSettings` | `apps/web/src/data/mutation-options/smart-return.ts` |
| Session clear | WP-04 `clearUserSessionQueries` (preserves nearby) |

Nearby uses `keepPreviousData` so background refetch does not blank the map.

## 4. Mutation ownership

| Mutation | Cache strategy |
|----------|----------------|
| Verify | `setQueryData` spot + patch nearby/my-spots → invalidate my-spots + gamification |
| Claim | same as verify |
| Create | invalidate my-spots + nearby root + gamification (no optimistic list row) |
| Report | invalidate `reportsKeys.all` |
| Smart Return plan/settings/cancel | `setQueryData(meKeys.smartReturn())` from response |

Idempotency keys remain on create/verify/claim (and media upload). Optimistic UI
is not the default for irreversible claim/verify.

## 5. Error strategy

Representative failures map through SDK error classes + page mappers:

| Status / code | UX |
|---------------|-----|
| 401 / session | AuthSession teardown + WP-03 redirect (not Query-owned) |
| 403 | FriendlyApiErrorMessage |
| 404 spot | EmptyState / `mapParkingActionError` / `mapParkingReportError` |
| 409 `ALREADY_VERIFIED` | friendly verify copy |
| 409 `DUPLICATE_REPORT` | friendly report copy |
| 422 validation | form / FriendlyApiErrorMessage |
| 429 / 5xx / offline / timeout | FriendlyApiErrorMessage + toast where mutations toast |

## 6. Loading contract

- Initial: LoadingState / skeletons when no data yet
- Background: keep prior nearby data (`keepPreviousData`); detail shows prior until replaced
- Mutation pending: disable submits; preserve form values
- Empty: EmptyState (my spots / reports / 404)
- Offline: existing OfflineBanner (not parking-specific)

## 7. Guardrails and CI

- Module: `scripts/architecture/wp05-core-parking-flows.mjs`
- Focused: `pnpm guardrails:wp05`, `pnpm --filter @parkio/web test:wp05` (`--retry=0`)
- Production `pnpm guardrails` includes WP-05 via `check-boundaries.mjs`
- CI job: “WP-05 core parking flows”

Rules:

- `wp05-page-parking-mutation-api`
- `wp05-page-report-mutation-api`
- `wp05-duplicate-spot-cache-helper`
- `wp05-page-smart-return-cache-write`

## 8. Remaining migration debt

- Dedicated `mutationOptions` helper from TanStack (plain option objects used today)
- Appeal / admin moderation mutation factories
- `getMySpot` owner-detail query (API exists; UI is list-only)
- `updateSmartReturnTime` dedicated hook if product surfaces mid-plan time edits beyond left-by-car
- Shared mobile parking mutation package (out of WP-05 Web scope)

## 9. Rules for future contributors

1. Do not call `parkingApi.verifySpot|claimSpot|createParkingSpot` from pages.
2. Do not reimplement `applyParkingSpotUpdate`.
3. Do not `setQueryData(meKeys.smartReturn())` from pages — use Smart Return hooks.
4. Do not redesign WP-03 routing or WP-04 QueryClient / keys.
5. Prefer `setQueryData` when the response is the entity; otherwise targeted invalidate.