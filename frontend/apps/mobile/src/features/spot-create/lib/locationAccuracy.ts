export const REQUIRED_GPS_ACCURACY_METERS = 75;
export const WARNING_GPS_ACCURACY_METERS = 35;

export function isGpsAccuracyAcceptable(accuracyMeters: number | null | undefined): boolean {
  return typeof accuracyMeters === 'number' && accuracyMeters > 0 && accuracyMeters <= REQUIRED_GPS_ACCURACY_METERS;
}

export function formatAccuracy(accuracyMeters: number | null | undefined): string {
  if (typeof accuracyMeters !== 'number' || accuracyMeters <= 0) return 'unknown accuracy';
  return accuracyMeters < 10 ? `${accuracyMeters.toFixed(1)} m` : `${Math.round(accuracyMeters)} m`;
}

/** User-facing GPS signal buckets derived from the same thresholds the submit gate uses. */
export type GpsSignalLevel = 'excellent' | 'good' | 'usable' | 'poor' | 'none';

export function gpsSignalLevel(accuracyMeters: number | null | undefined): GpsSignalLevel {
  if (typeof accuracyMeters !== 'number' || accuracyMeters <= 0) return 'none';
  if (accuracyMeters <= 10) return 'excellent';
  if (accuracyMeters <= WARNING_GPS_ACCURACY_METERS) return 'good';
  if (accuracyMeters <= REQUIRED_GPS_ACCURACY_METERS) return 'usable';
  return 'poor';
}
