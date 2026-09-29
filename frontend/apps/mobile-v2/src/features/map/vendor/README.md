# Generated MapLibre runtime (mobile-v2 WebView)

This directory holds the **exact** MapLibre GL JS 6.4.1 runtime
derived from the official npm package for offline / non-CDN WebView execution.

MapLibre v6 publishes ESM-only (`maplibre-gl.mjs`). The checked-in
`maplibre-gl.js` is an esbuild IIFE bundle (`global-name=maplibregl`) so the
existing classic inline `<script>` WebView document keeps working.

| Field | Value |
|-------|-------|
| Package | maplibre-gl@6.4.1 |
| Integrity | `sha512-KzxQKtfBu/pSz1C+yW1hNS9eyj2h2lC7ufdAi6/SEt177n3oAfDfmUmslRfJdXY7ReAFBcnvwsqmiyoDhtA9GQ==` |
| JS SHA-256 | `1844610ab53b72a0f42d91c24bd602adbdbd3ea2fefddaad7dae6f7428ce4b92` |
| CSS SHA-256 | `8e2dbbab312dc57656fbb76e9fa5308c75c9d7c7ba5808a7d55bcdb64cc813fa` |
| License | BSD-3-Clause |
| Runtime mode | esbuild IIFE from ESM |

Do not manually edit `maplibre-gl.js`, `maplibre-gl.css`, or
`maplibreRuntime.generated.ts`.

Regenerate:

```bash
node apps/mobile-v2/scripts/generate-maplibre-runtime.mjs
```
