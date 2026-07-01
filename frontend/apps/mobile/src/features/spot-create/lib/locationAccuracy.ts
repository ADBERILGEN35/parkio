export const REQUIRED_GPS_ACCURACY_METERS = 75;
export const WARNING_GPS_ACCURACY_METERS = 35;

export function isGpsAccuracyAcceptable(accuracyMeters: number | null | undefined): boolean {
  return typeof accuracyMeters === 'number' && accuracyMeters > 0 && accuracyMeters <= REQUIRED_GPS_ACCURACY_METERS;
}

export function formatAccuracy(accuracyMeters: number | null | undefined): string {
  if (typeof accuracyMeters !== 'number' || accuracyMeters <= 0) return 'unknown accuracy';
  return accuracyMeters < 10 ? `${accuracyMeters.toFixed(1)} m` : `${Math.round(accuracyMeters)} m`;
}
