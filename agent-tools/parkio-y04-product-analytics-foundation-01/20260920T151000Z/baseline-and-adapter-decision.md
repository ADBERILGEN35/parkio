# Y04 — Baseline and adapter decision

**Package:** PARKIO-Y04-PRODUCT-ANALYTICS-FOUNDATION-01  
**API baseline (verified):** `4a9ba2184e867b8ea8b927f7c3f8bcb137c2760a`  
**Observed `origin/api` after fetch:** identical (no delta)

## Adapter decision

| Option | Decision |
|--------|----------|
| PostHog JS / RN SDK | **Not installed** |
| PostHog HTTP `/batch/` capture | **Implemented** as optional vendor transport |
| LocalCaptureTransport | **Implemented** for isolated acceptance |

### Why HTTP capture instead of posthog-js / posthog-react-native

Official PostHog web SDK enables **autocapture by default**. Session replay is a separate plugin but easy to mis-enable. The Y01 / Y04 contract requires:

- no automatic broad click capture
- no form/content capture
- session replay **DISABLED**
- only approved event inventory

A narrow HTTP batch adapter behind `@parkio/product-analytics` → existing `setProductAnalyticsTransport` / `trackProductEvent` seam satisfies the contract **by construction**: no SDK means no autocapture and no session replay module.

Compatibility (docs-verified, not vendor-provisioned):

- Web: Vite + React Router 6 — events emitted from app-level transitions
- Mobile-v2: Expo 56 / RN 0.85 / expo-router — AppState + pathname tracking; MapLibre WebView messages remain untrusted and are not a new bridge surface for analytics

### Defaults

| Setting | Value |
|---------|-------|
| Consent | `unset` (disabled) |
| Vendor enabled | `false` unless `VITE_PRODUCT_ANALYTICS_VENDOR_ENABLED` / `EXPO_PUBLIC_PRODUCT_ANALYTICS_VENDOR_ENABLED=true` |
| Autocapture | N/A (no SDK) — explicit `$autocapture_disabled: true` on batch payload |
| Session replay | DISABLED — `$session_recording_enabled: false`; no replay package |
| Heartbeat | **0** (disabled) — prefer local accumulation + `screen_engagement_summary` on exit |
| Idle timeout | **60_000 ms** |

### Activation env (do not put secrets in chat)

Web: `VITE_PRODUCT_ANALYTICS_VENDOR_ENABLED`, `VITE_POSTHOG_KEY`, `VITE_POSTHOG_HOST`  
Mobile: `EXPO_PUBLIC_PRODUCT_ANALYTICS_VENDOR_ENABLED`, `EXPO_PUBLIC_POSTHOG_KEY`, `EXPO_PUBLIC_POSTHOG_HOST`

Hosts matching `example.invalid` / `analytics.test` are blocked.

## Non-dependencies

Does **not** depend on unmerged PR #52 / #53 / #54 (Slack / New Relic). Independent branch from `api`.
