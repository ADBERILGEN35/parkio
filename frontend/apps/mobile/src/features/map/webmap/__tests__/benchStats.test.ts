import { computeFrameStats } from '../benchStats';

describe('computeFrameStats', () => {
  it('returns all-zero stats for empty input', () => {
    const s = computeFrameStats([], 16.7);
    expect(s).toEqual({
      p50: 0, p90: 0, p95: 0, p99: 0,
      jank: 0, jankPct: 0, avg: 0, max: 0, frames: 0, thresholdMs: 16.7,
    });
  });

  it('counts a frame as janky only when it exceeds the threshold', () => {
    // 6 smooth (16ms) + 4 janky (>16.7ms) = 40% jank.
    const frames = [16, 16, 16, 16, 16, 16, 20, 33, 50, 100];
    const s = computeFrameStats(frames, 16.7);
    expect(s.frames).toBe(10);
    expect(s.jank).toBe(4);
    expect(s.jankPct).toBe(40);
    expect(s.max).toBe(100);
  });

  it('computes percentiles from the sorted distribution', () => {
    const frames = Array.from({ length: 100 }, (_, i) => i + 1); // 1..100
    const s = computeFrameStats(frames, 1000);
    expect(s.p50).toBeLessThan(s.p90);
    expect(s.p90).toBeLessThanOrEqual(s.p95);
    expect(s.p95).toBeLessThanOrEqual(s.p99);
    expect(s.p99).toBeLessThanOrEqual(100);
    expect(s.avg).toBe(50.5);
    expect(s.jank).toBe(0); // threshold 1000 > every frame
  });

  it('rounds reported values to one decimal place', () => {
    const s = computeFrameStats([10, 11, 12], 16.7);
    expect(s.avg).toBe(11); // (10+11+12)/3 = 11
    expect(Number.isInteger(s.p95 * 10)).toBe(true);
  });
});
