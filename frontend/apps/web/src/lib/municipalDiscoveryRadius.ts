/**
 * Authenticated /map municipal discovery radius (WEB-MUNI).
 * Independent of community nearby `radius` (which may be omitted → server 1000 m).
 */

/** Default search radius for municipal facilities — aligned with public Explore. */
export const DEFAULT_MUNICIPAL_RADIUS_METERS = 5_000;

/** Matches parking-service MunicipalFacilityQueryService max (1..50000). */
export const MAX_MUNICIPAL_RADIUS_METERS = 50_000;

export const MIN_MUNICIPAL_RADIUS_METERS = 1;

/**
 * Supported selectable steps (meters). Includes the default and values up to the API max.
 * Expansion walks to the next strictly larger step.
 */
export const MUNICIPAL_RADIUS_PRESETS_METERS = [
  1_000, 2_000, 3_000, 5_000, 10_000, 25_000, 50_000,
] as const;

export type MunicipalRadiusPresetMeters = (typeof MUNICIPAL_RADIUS_PRESETS_METERS)[number];

export function clampMunicipalRadiusMeters(value: number): number {
  if (!Number.isFinite(value)) {
    return DEFAULT_MUNICIPAL_RADIUS_METERS;
  }
  const truncated = Math.trunc(value);
  if (truncated < MIN_MUNICIPAL_RADIUS_METERS) {
    return MIN_MUNICIPAL_RADIUS_METERS;
  }
  if (truncated > MAX_MUNICIPAL_RADIUS_METERS) {
    return MAX_MUNICIPAL_RADIUS_METERS;
  }
  return truncated;
}

/** True when the value is a supported explicit municipal radius (1..max). */
export function isValidMunicipalRadiusMeters(value: unknown): value is number {
  return (
    typeof value === 'number' &&
    Number.isFinite(value) &&
    Number.isInteger(value) &&
    value >= MIN_MUNICIPAL_RADIUS_METERS &&
    value <= MAX_MUNICIPAL_RADIUS_METERS
  );
}

/**
 * Next larger preset above {@code current}, or null when already at/above the API max
 * (no expansion offer).
 */
export function nextMunicipalRadiusMeters(current: number): number | null {
  const clamped = clampMunicipalRadiusMeters(current);
  for (const preset of MUNICIPAL_RADIUS_PRESETS_METERS) {
    if (preset > clamped) {
      return preset;
    }
  }
  return null;
}

/** Human-facing label: meters under 1000, otherwise whole kilometers when divisible. */
export function formatMunicipalRadiusLabel(meters: number): string {
  const value = clampMunicipalRadiusMeters(meters);
  if (value >= 1000 && value % 1000 === 0) {
    return `${value / 1000} km`;
  }
  return `${value} m`;
}
