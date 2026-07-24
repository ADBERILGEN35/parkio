# Sprint 2.4 WP-03 Routing Architecture

## Status and scope

WP-03 is the Web routing-consolidation boundary for Sprint 2.4. This record
describes the implementation enforced by the repository; it is not an
executable route definition. The canonical manifest remains the only
executable source of route paths and route policy metadata.

This work package does not own React Query cache policy, feature-data
migration, backend behavior, SDK contracts, or Mobile/Mobile-v2 routing.
Those concerns remain in WP-04 or later work packages.

WP-03 closure is not declared by this document. Remediation phases may still
be open; treat verification commands and fixtures as the enforceable truth.

## Canonical ownership

- `apps/web/src/routing/route-manifest.ts` owns the immutable route graph and
  all route IDs, parent relationships, paths, access/bootstrap/lifecycle/role
  policy, redirects, navigation metadata, document-title metadata,
  navigation-interruption eligibility, loading policy, fallback policy,
  shell ownership, and parameter validation.
- `apps/web/src/routing/create-app-router.tsx` compiles that manifest into
  browser and memory React Router objects. It does not redefine route
  metadata. Cardinality enforcement permits one `createBrowserRouter` owner
  and one `createMemoryRouter` owner in that approved compiler file, and
  rejects a second factory of the same kind.
- `apps/web/src/routing/route-elements.tsx` is the route-element registry. It
  maps manifest component keys to eager or lazy elements and owns the shared
  and profile-specific lazy fallbacks; it does not own paths or policy.
- `apps/web/src/app/runtime.ts` creates and owns exactly one router for each
  `WebAppRuntime`. Production runtimes receive one browser router; tests
  receive independent memory routers. Runtime disposal owns router disposal.
- `apps/web/src/App.tsx` is the sole production `RouterProvider` owner and
  renders the router owned by its runtime. Canonical-file approval is
  cardinality-aware: a second production `RouterProvider` inside `App.tsx`
  is rejected.
- `apps/web/src/routing/RoutePolicyBoundary.tsx` is the sole authentication-,
  lifecycle-, and role-aware routing policy owner. Manifest consumption alone
  does not authorize a second Navigate/Outlet policy wrapper elsewhere.
- Shell navigation is derived from manifest `navigation` and
  `shellNavigation` metadata. Shells render that derived data and do not own
  route destinations, labels, icons, ordering, grouping, or visibility
  policy.
- `RouteAccessibility` resolves document-title keys through the canonical
  manifest. There is no path-to-title registry.
- The upload unsaved-changes boundary resolves bypass eligibility through the
  canonical manifest (`isNavigationInterruptionBypassPath`). There is no
  independent pathname allowlist.

## Allowed consumers

Application shells, navigation renderers, redirect sanitization, accessibility
composition, authentication bootstrap classification, and unsaved-navigation
handling may consume manifest entries or exported manifest-derived helpers.
The compiler and element registry may consume route metadata to create React
Router objects and elements.

A consumer does not become an authority: it must not add fallback paths,
literal route tables, duplicated policy, or a second interpretation that can
diverge from the manifest. Feature pages may use ordinary `Link` destinations
for local user actions, and may use page-local hook state with `Navigate` /
`Outlet` for non-authentication, non-lifecycle, non-role wizard flows that do
not own a route graph or persistent route registry.

## Forbidden patterns

The Web application must not introduce:

- a second route manifest or route graph (module-scope, function-local,
  named export, direct or identifier default export, alias, computed key,
  spread composition, or transparent call wrappers such as `Object.freeze`);
- a second production router, duplicate router factory of the same kind, or
  duplicate `RouterProvider` inside an approved canonical file;
- an independent redirect registry;
- an independent navigation registry;
- an independent path-to-title or path-to-accessibility registry, including
  function-local registries;
- an independent navigation-interruption pathname allowlist, including
  `Object.freeze(new Set([...]))`, spread-composed Sets/arrays, and boolean
  maps;
- multi-path routing-policy classifiers expressed through repeated pathname
  equality, logical compositions, membership checks, or `switch(pathname)`
  (including statically resolvable aliases) when they own authentication,
  lifecycle, role, redirect, title, accessibility, or navigation-interruption
  policy;
- authentication-aware routing wrappers outside `RoutePolicyBoundary`,
  including a second manifest-aware Navigate/Outlet gate;
- legacy `ProtectedRoute` or `RoleRoute` policy ownership;
- feature-owned route metadata or feature-local React Router graphs.

The architecture guardrails apply semantic AST checks to production Web
source. Claims below match fixtures in `scripts/architecture/guardrails.test.mjs`
and must not be read as broader than those fixtures prove.

## Guardrail coverage (fixture-backed)

Proven positive detections include:

- function-local literal route graphs;
- direct and identifier default-exported route graphs;
- `Object.freeze`-wrapped route graphs and route-policy Sets;
- spread-composed route-policy collections;
- `switch(pathname)` and aliased-discriminant multi-path classifiers;
- a second manifest-aware authentication wrapper;
- two `RouterProvider`s inside the approved App-style file;
- duplicate same-kind router factories inside an approved compiler file;
- function-local title and navigation-interruption registries.

Proven negative (accepted) cases include:

- page-local non-auth wizards using hook state, `Navigate`, and `Outlet`;
- ordinary help links and translation resources;
- API endpoint collections and page-local soft `{ path, title }` step arrays
  that do not escape as authorities;
- test-only route fixtures and manifest-derived consumers;
- ordinary feature path comparisons without routing-policy ownership;
- a single canonical `RouterProvider` and the legitimate
  `RoutePolicyBoundary`.

## Adding or changing a route

1. Update the canonical route manifest.
2. Register the page through the existing route-element registry mechanism.
3. Define every applicable lifecycle, role, redirect, title, interruption,
   loading, fallback, shell, navigation, and parameter-validation field in the
   manifest.
4. Add or update English and Turkish localization keys without creating a
   path-based translation registry.
5. Update manifest invariants, compiler behavior tests, policy tests, and
   focused browser acceptance for the changed observable behavior.
6. Run the architecture guardrails and the focused WP-03 browser acceptance
   suite.
7. Do not create feature-local route arrays, pathname allowlists, redirect
   maps, or navigation authorities.

## Verification commands

Run from `frontend/`:

```sh
pnpm guardrails
pnpm guardrails:test
pnpm guardrails:lint
node scripts/architecture/check-public-exports.mjs
pnpm --filter @parkio/web exec playwright install chromium
pnpm --filter @parkio/web exec playwright test e2e/wp03-routing.spec.ts --project=wp03-chromium --workers=1
pnpm --filter @parkio/web exec playwright test --project=chromium --workers=1
pnpm --filter @parkio/web test
pnpm --filter @parkio/web typecheck
pnpm --filter @parkio/web lint
pnpm --filter @parkio/web build
```

## Focused browser hermetic policy

The focused WP-03 Chromium suite (`e2e/wp03-routing.spec.ts`, project
`wp03-chromium`) is frontend-controlled and requires no backend or seeded
account. Allowed request categories are:

1. `local-vite-origin` — the Playwright Vite origin (application assets, HMR,
   and source modules);
2. `mocked-frontend-api` — explicitly intercepted `/api/v1/**` handlers that
   return fixture JSON (unmocked API paths fail with `NOT_MOCKED`);
3. `deterministic-external-asset-stub` — aborted or stubbed known asset hosts
   (including Unsplash hero images, OpenStreetMap/MapTiler tiles, and Google
   Fonts). Unrecognized external hosts abort and fail the suite.

The real-stack Playwright workflow remains manual evidence and is not a WP-03
merge gate.
