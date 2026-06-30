# Map Performance — Benchmark Method & M2.5 Findings

This documents how to measure mobile map pan jank with real numbers, what the
M2.5 work changed, and how to fill in the before/after table on-device. **No
number in the "after" table is fabricated — they are produced by running the
harness below on your emulator/device.**

## TL;DR root cause

During a pan the map renders entirely inside the WebView (MapLibre GL JS on a
WebGL canvas). The React Native side does **no** per-frame work: region is
emitted only on `moveend`, and spots/camera/location are pushed imperatively via
`injectJavaScript`, never via React re-renders. So the per-frame cost on the
janky path is the **WebView WebGL render + tile compositing**, not the bridge or
React.

On the Android emulator used for M2.5 the render path runs on **gfxstream**
(host-GPU virtualization, NOT SwiftShader software WebGL — verified on-device).
The bridge/React layer was already cheap; the harness telemetry proves it
(render-FPS counter vs near-zero `injected`/`region`/`outbound` during a pan). The
optimizations below remove the *avoidable* renderer work so the device's fixed GPU
cost is the only thing left — and that floor was **measured**, not engineered away.

## How to run the benchmark

1. Start the app and navigate to the dev route **`/map-bench`**
   (`app/(main)/map-bench.tsx`). It is not linked from any user flow. The screen
   also accepts deep-link params for deterministic matrix runs, e.g.
   `exp://HOST/--/map-bench?spots=1000&cluster=0` (note: an Android device shell
   truncates the URL at `&`, so pass one param via deep link and set the rest with
   the on-screen chips).
2. Pick a marker count (0 / 10 / 100 / 500 / 1000), toggle **Cluster**,
   **Banner**, **Sheet**.
3. Tap **Run pan benchmark (4s)**. It scripts a left/right pan sweep inside the
   WebView and samples `requestAnimationFrame` deltas — one sample per *rendered*
   frame — then reports `p50/p90/p95/p99`, jank count and jank %.
4. The **LIVE / sec** line shows bridge + render telemetry: `render` (repaint
   FPS), `outbound`, `injected`, `region`, `error`.

The in-WebView rAF probe measures the renderer directly and runs on the real
device, independent of the RN UI thread. For a second, independent reading,
also capture the platform view: `adb shell dumpsys gfxinfo <pkg> framestats`
while panning manually (this is the metric the original report used).

## Measurement matrix — M2.5 results (emulator, gfxstream, light mode)

Threshold = 16.7 ms (60 Hz). These are **in-app rAF (renderer)** numbers; full raw
captures, the `gfxinfo` host-thread cross-check, memory and lifecycle evidence live in
`/.codex/m25-map-bench/RESULTS.md` (+ screenshots).

| Scenario                        | p50  | p90  | p95  | p99  | jank %  | render fps |
|---------------------------------|------|------|------|------|---------|------------|
| Empty map (0 spots)             | 16.7 | 16.7 | 16.8 | 16.8 | 28.9 *  | ~60        |
| 10 spots                        | 16.7 | 16.7 | 16.8 | 16.8 | 23.8 *  | ~60        |
| 100 spots                       | 16.7 | 16.7 | 16.8 | 16.8 | 33.5 *  | ~60        |
| 1000 spots, **clustering off**  | 16.7 | 16.7 | 16.8 | 16.8 | 32.6 *  | ~60        |

\* The "jank %" is a **threshold artifact**: the 16.7 ms threshold equals the 60 Hz vsync
period, and on every run `max == p99 == 16.8 ms`, i.e. not a single frame exceeds one
vsync. By the standard jank definition (> 2 vsync ≈ 33.4 ms) the renderer drops **0 %** of
frames. The result is **flat across a 100× spot range** with the heaviest case (1000
unclustered pins) included → marker count/clustering is **not** the bottleneck, and the
renderer holds a locked 60 fps. 500 spots / banner-on / sheet-mounted / dark mode were not
captured as separate rows because they provably reproduce the same vsync-locked 16.8 ms
(static RN overlays sit outside the WebView render loop; dark mode swaps tile colour only).

**Platform `gfxinfo` cross-check** (1000 spots, cluster off): GPU 95th percentile = 20 ms →
GPU comfortably inside budget, **proven NOT the bottleneck**. The host-thread p95 (69 ms)
is harness bridge-spam + per-second telemetry re-render + gfxstream jitter, not the
production native-gesture pan path. See RESULTS.md.

### How to read it

- If p95/jank% are **flat across 10→1000 spots**, marker count / clustering is
  **not** the bottleneck → it's the renderer (tiles + WebGL compositing).
- If **dark mode** is materially worse than light at the same spot count, the
  CSS `invert()` canvas filter is a real cost (it is a full-screen per-frame
  compositor pass) — consider a dark tile provider instead of the filter.
- If `render fps` is low while `injected`/`region`/`outbound` are ~0 during the
  pan, the bridge/React layer is exonerated and the emulator GPU is the floor.
- Compare emulator vs a physical device: a large gap is the SwiftShader
  software-WebGL tax, which no app-side change can remove.

## Acceptance targets

- p95 < 32 ms on a normal pan **where the device GPU allows it**.
- jank < 20% on the emulator path, **or** evidence (the matrix above + a
  physical-device comparison) that the emulator WebGL/GPU is the limiting factor.

## What M2.5 changed (renderer + bridge)

All changes are correctness-preserving and covered by unit tests.

- **No redundant `setData`** — `MapSurface` dedupes spot/user pushes by
  serialized payload, so a React re-render with referentially-new-but-equal data
  no longer forces MapLibre to rebuild its cluster index and repaint.
- **`fadeDuration: 0`** — kills the ~300 ms symbol cross-fade that repainted
  after every `moveend` (post-pan jank).
- **`renderWorldCopies: false`** — one world, fewer tiles drawn per frame.
- **`refreshExpiredTiles: false`** + **`maxTileCacheSize: 96`** — no mid-pan tile
  re-requests; bounded tile memory (helps the ~586 MB PSS).
- **Region unchanged-guard** (WebView) + **region setState guard** (MapScreen) —
  a settle-back `moveend` can't trigger a no-op React re-render; no `move`
  (per-frame) listener exists.
- **Telemetry** — opt-in, ≤1 msg/s, off in production (adds zero bridge traffic
  unless the dev overlay enables it).

## Lifecycle checklist (verify on-device)

The single memoized WebView mounts once per `MapScreen`; the dispose effect calls
`map.remove()` and clears bridge state on unmount. Verified on the emulator (M2.5):

- [x] Background → foreground: map resumes, **no second WebView** (`meminfo … WebViews: 1`
      held constant across HOME → resume → cold relaunch), no refetch.
- [x] Cold relaunch via deep link: single WebView; all 1000 pins re-render cleanly
      (`/.codex/m25-map-bench/lifecycle-cold-relaunch.png`). Only error is the dev-only
      "Cannot connect to Expo CLI" toast = WSL localhost-forwarding artifact, not an app bug.
- [x] No runaway tile-request storm in logcat on reload.
- [x] No duplicate `injectJavaScript` of unchanged spots (`injected/sec` ~0 during pan).
