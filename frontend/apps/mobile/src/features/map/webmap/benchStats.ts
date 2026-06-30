/**
 * Frame-time statistics for the map benchmark.
 *
 * This function is intentionally written in plain, self-contained ES5 (no
 * closures over module scope, no TS-only runtime constructs) so its source can
 * be serialized with `Function.prototype.toString()` and embedded verbatim into
 * the WebView bench harness (see {@link ./mapHtml}). That guarantees the code we
 * unit-test here is the exact code that runs on the emulator — the numbers are
 * computed one way, in one place.
 */

export interface FrameStats {
  /** Median frame interval (ms). */
  p50: number;
  p90: number;
  p95: number;
  p99: number;
  /** Count of frames slower than the jank threshold. */
  jank: number;
  /** Janky frames as a percentage of all sampled frames. */
  jankPct: number;
  /** Mean frame interval (ms). */
  avg: number;
  /** Slowest single frame (ms). */
  max: number;
  /** Total sampled frames. */
  frames: number;
  /** Jank threshold used (ms). */
  thresholdMs: number;
}

/**
 * Compute percentile / jank statistics from a list of frame intervals (ms).
 * A frame is "janky" when its interval exceeds `thresholdMs` (default caller
 * passes 16.7ms for a 60Hz budget). Empty input yields an all-zero result.
 */
/* eslint-disable no-var */
export function computeFrameStats(frames: number[], thresholdMs: number): FrameStats {
  if (!frames || frames.length === 0) {
    return { p50: 0, p90: 0, p95: 0, p99: 0, jank: 0, jankPct: 0, avg: 0, max: 0, frames: 0, thresholdMs: thresholdMs };
  }
  var sorted = frames.slice().sort(function (a, b) {
    return a - b;
  });
  var pct = function (p: number) {
    var idx = Math.floor((p / 100) * sorted.length);
    if (idx >= sorted.length) idx = sorted.length - 1;
    return sorted[idx];
  };
  var jank = 0;
  var sum = 0;
  var max = 0;
  for (var i = 0; i < frames.length; i++) {
    var d = frames[i];
    sum += d;
    if (d > max) max = d;
    if (d > thresholdMs) jank++;
  }
  var round1 = function (n: number) {
    return Math.round(n * 10) / 10;
  };
  return {
    p50: round1(pct(50)),
    p90: round1(pct(90)),
    p95: round1(pct(95)),
    p99: round1(pct(99)),
    jank: jank,
    jankPct: round1((jank / frames.length) * 100),
    avg: round1(sum / frames.length),
    max: round1(max),
    frames: frames.length,
    thresholdMs: thresholdMs,
  };
}
/* eslint-enable no-var */
