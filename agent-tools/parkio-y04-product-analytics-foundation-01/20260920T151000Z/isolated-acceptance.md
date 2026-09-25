# Y04 — Isolated acceptance

## Environments

| Surface | Result | Identity |
|---------|--------|----------|
| `@parkio/product-analytics` vitest | **PASS** 12/12 | package unit |
| `@parkio/validation` spa-telemetry | **PASS** 7/7 | sanitizer |
| `@parkio/web` productAnalytics.y04.test | **PASS** 3/3 | local capture |
| `@parkio/mobile-v2` productAnalytics.test | **PASS** 3/3 | Jest |
| Web actual app (browser UI) | **PARTIAL** — bootstrap + RouteAccessibility wired; exercised via unit/local capture, not full Playwright E2E this package | — |
| RN actual app on emulator | **NOT_EXECUTED** | AVD `Pixel_8` listed; no running device; no Expo assemble/run performed |

## Checklist

1. Disabled → no events — **PASS** (unit)
2. Activation → approved set — **PASS** (unit)
3. Opt-out discards / stops — **PASS** (unit)
4. Screen entry/exit + normalization — **PASS** (unit)
5. Foreground / background / idle — **PASS** (ActiveTimeTracker unit)
6. Duplicate / OOO checkpoint — **PASS** (unit)
7. Logout A→B isolation — **PASS** (unit)
8. Offline queue / restart — **PARTIAL** (enqueue on transport failure covered; full process restart not E2E)
9. Map → preview → detail → return — **PASS** (unit sequence) + source instrumentation present
10. Map failure / retry — **PASS** (unit) + mobile MapSurface instrumentation
11. Sensitive / URL canaries — **PASS** (strict sanitizer)
12. Transport failure leave app usable — **PASS** (fail-open design; NullTransport default)
13. No new unrestricted WebView bridge — **PASS** (no bridge changes for analytics)
14. PA-06 public null unchanged — **PASS** (no code path altered)

## Remaining for RN actual-app

1. Start `Pixel_8` emulator  
2. `pnpm --filter @parkio/mobile-v2 android` (or Expo Go) against local capture / vendor disabled  
3. Grant analytics toggle; walk map → preview → detail → back; background app; verify LocalCapture / logs  
4. Opt-out and confirm no further events  

**Do not claim full behavioral slice acceptance for RN until the above runs.**
