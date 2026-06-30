/**
 * Deterministic SYNTHETIC spot fixtures for the map performance benchmark.
 *
 * These are NOT real parking data and must never reach a production code path —
 * they exist only to load the renderer with a controlled, repeatable marker
 * count (10 / 100 / 500 / 1000) so pan jank can be measured against marker
 * density. A seeded PRNG makes every run identical, so before/after numbers are
 * comparable. Every generated id is prefixed `bench-` so synthetic spots are
 * always distinguishable from real ones.
 */
import {
  LEGAL_STATUSES,
  PARKING_STATUSES,
  type PublicSpot,
} from '@parkio/types';

/** Mulberry32 — tiny, fast, deterministic PRNG (no external dep). */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export interface BenchSpotsOptions {
  /** Center to scatter spots around. */
  center: { lat: number; lng: number };
  /** How many synthetic spots to generate. */
  count: number;
  /** Half-extent of the scatter box in degrees (~0.03 ≈ 3 km). */
  spreadDeg?: number;
  /** PRNG seed; same seed → identical spots. */
  seed?: number;
}

/**
 * Generate `count` deterministic synthetic {@link PublicSpot}s scattered around
 * `center`. Statuses cycle through the real enum so tone coloring and clustering
 * behave as they would with live data.
 */
export function generateBenchSpots({
  center,
  count,
  spreadDeg = 0.03,
  seed = 1337,
}: BenchSpotsOptions): PublicSpot[] {
  const rand = mulberry32(seed);
  const now = Date.now();
  const spots: PublicSpot[] = [];
  for (let i = 0; i < count; i++) {
    // Bias toward the center (squared random) so clusters form naturally.
    const r = Math.sqrt(rand());
    const theta = rand() * Math.PI * 2;
    const lat = center.lat + Math.cos(theta) * r * spreadDeg;
    const lng = center.lng + Math.sin(theta) * r * spreadDeg;
    const status = PARKING_STATUSES[i % PARKING_STATUSES.length];
    const legalStatus = LEGAL_STATUSES[i % LEGAL_STATUSES.length];
    const iso = new Date(now - i * 60_000).toISOString();
    spots.push({
      id: `bench-${i}`,
      mediaId: `bench-media-${i}`,
      latitude: lat,
      longitude: lng,
      addressText: null,
      description: null,
      manualLocationEdited: false,
      suitableVehicleTypes: ['ANY'],
      parkingContext: 'UNKNOWN',
      legalStatus,
      violationReasons: [],
      status,
      expiresAt: new Date(now + 3_600_000).toISOString(),
      createdAt: iso,
      updatedAt: iso,
    });
  }
  return spots;
}
