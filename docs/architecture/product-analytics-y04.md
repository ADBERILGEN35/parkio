# Product analytics foundation (Y04)

Client product analytics (`client.analytics.v1`) is implemented behind the existing
`trackProductEvent` / `setProductAnalyticsTransport` seam.

- Package: `@parkio/product-analytics`
- Adapter: PostHog HTTP batch (optional); **no** posthog-js / RN SDK (autocapture/session replay avoided by construction)
- Default: consent unset → no capture; vendor flag off
- Active time: idle 60s; heartbeat disabled (local accumulation + exit summary)
- Session replay: disabled

Activation env names (values are secrets — configure in deploy systems only):

- Web: `VITE_PRODUCT_ANALYTICS_VENDOR_ENABLED`, `VITE_POSTHOG_KEY`, `VITE_POSTHOG_HOST`
- Mobile-v2: `EXPO_PUBLIC_PRODUCT_ANALYTICS_VENDOR_ENABLED`, `EXPO_PUBLIC_POSTHOG_KEY`, `EXPO_PUBLIC_POSTHOG_HOST`

Real vendor ingestion and real-user activation are outside instrumentation packages.
