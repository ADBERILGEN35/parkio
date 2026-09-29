# Y04 — Event instrumentation matrix

Schema version: `CLIENT_ANALYTICS_SCHEMA_VERSION = 1` (`client.analytics.v1`)

| Event | Producer | Insertion point | Trigger | Allowed props | Dedup | Missing/failed |
|-------|----------|-----------------|---------|---------------|-------|----------------|
| `analytics_session_started` | Client | `ProductAnalyticsClient.setConsent('granted')` / init if stored granted | Consent granted | schemaVersion, platform | One per consent session | Drop if consent denied |
| `analytics_session_ended` | Client | opt-out / logout reset | Consent denied or identity reset | sessionEndReason, schemaVersion | — | Local mirror on opt-out |
| `screen_viewed` | Web/Mobile | `RouteAccessibility` / `_layout` pathname | Route change (pathname only) | screenName, previousScreenName | Per navigation | No query/hash |
| `screen_engagement_summary` | Client | ActiveTimeTracker screen exit | Leave screen / logout | activeDurationMs, incomplete, checkpointSeq | Checkpoint seq monotonic | incomplete=true if abrupt |
| `map_ready` | Web/Mobile | NearbySpotsMap onLoad; map.tsx onReady | MapLibre load / bridge ready | platform | Soft | — |
| `map_init_failed` | Web/Mobile | NearbySpotsMap onError; MapSurface error | Load/bridge error | mapErrorCode | Soft | Bounded codes only |
| `filter_applied` | Web/Mobile | MunicipalFacilityResults; map filter wrappers | Filter UI change | filterKind | Soft | Coarse enum |
| `facility_preview_opened` | Web/Mobile | MapPage selectMunicipal; map selectMunicipal | Marker/list select | selectionOrigin | Soft | No facility id |
| `facility_detail_opened` | Web/Mobile | Preview link / onOpenDetail | Navigate to detail | selectionOrigin | Soft | No facility id |
| `returned_to_map` | Web/Mobile | Detail back link / ScreenHeader onBack | Explicit return | — | Soft | — |
| `retry_attempted` / `retry_outcome` | Mobile | MapSurface retry path | User retry after map error | mapErrorCode, retryOutcome | Soft | — |
| `auth_login_*` | Web/Mobile | LoginPage / login.tsx | Submit / API result | authFailureReason on fail | Soft | Client-observed API only — not authoritative authz |
| `auth_signup_*` | Web/Mobile | RegisterPage / register.tsx | Submit / API result | authFailureReason on fail | Soft | **Not** “registration completed” business event |

Existing WP-SPA-12 funnel events unchanged via `spaTelemetry.ts`.

**PA-06:** `communitySpotCountInScope` remains null on public explore — analytics does not invent aggregates.
