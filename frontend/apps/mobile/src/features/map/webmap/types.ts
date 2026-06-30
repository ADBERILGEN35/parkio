import type { LatLng } from '@parkio/geo';

/** Geographic bounds reported by the map after movement. */
export interface MapBounds {
  north: number;
  south: number;
  east: number;
  west: number;
}

/** Current camera state, emitted on every `moveend`. */
export interface MapRegion {
  center: LatLng;
  zoom: number;
  bounds: MapBounds;
}

/** Per-second bridge/render counters reported when telemetry is enabled. */
export interface MapTelemetry {
  /** Messages WebView→RN in the last second. */
  outbound: number;
  /** Inbound bridge commands (injectJavaScript) received in the last second. */
  injected: number;
  /** Region (moveend) events emitted in the last second. */
  region: number;
  /** MapLibre repaint frames in the last second ≈ render FPS. */
  render: number;
  /** Renderer errors in the last second. */
  error: number;
}

/** Frame-time result from the benchmark harness (mirrors {@link ./benchStats}). */
export interface MapBenchResult {
  p50: number;
  p90: number;
  p95: number;
  p99: number;
  jank: number;
  jankPct: number;
  avg: number;
  max: number;
  frames: number;
  thresholdMs: number;
  /** Present only when the bench could not run (e.g. map not ready). */
  error?: string;
}

/** Messages the WebView map posts back to React Native. */
export type MapOutboundMessage =
  | { type: 'ready' }
  | { type: 'error'; reason: string }
  | { type: 'region'; center: LatLng; zoom: number; bounds: MapBounds }
  | { type: 'spotPress'; spotId: string }
  | { type: 'mapPress' }
  | ({ type: 'telemetry' } & MapTelemetry)
  // The WebView posts RAW frame intervals; React Native computes the stats with
  // the shared computeFrameStats (Hermes-safe — see mapHtml bench harness).
  | { type: 'bench'; frames?: number[]; thresholdMs?: number; error?: string };

/** Imperative API exposed by the isolated map renderer. */
export interface MapSurfaceHandle {
  /** Move the camera; `animate` eases, otherwise it jumps. */
  setCamera(center: LatLng, zoom?: number, animate?: boolean): void;
  /** Fit the viewport to bounds with padding (px). */
  fitBounds(bounds: MapBounds, padding?: number): void;
  /** Start/stop the once-per-second telemetry stream (dev/bench only). */
  setTelemetry(enabled: boolean): void;
  /** Run the scripted pan benchmark; result arrives via `onBench`. */
  runBenchmark(opts?: { durationMs?: number; thresholdMs?: number }): void;
}
