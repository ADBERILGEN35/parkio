/** Browser geolocation outcome for Parking Session start (no parallel location service). */
export type BrowserGeolocationFailure =
  | 'unsupported'
  | 'denied'
  | 'unavailable'
  | 'timeout';

export type BrowserGeolocationResult =
  | { ok: true; latitude: number; longitude: number }
  | { ok: false; reason: BrowserGeolocationFailure };

const DEFAULT_OPTIONS: PositionOptions = {
  enableHighAccuracy: true,
  timeout: 15_000,
  maximumAge: 0,
};

/**
 * One-shot current-position read used by Park Here.
 * Mirrors the MapPage Geolocation API surface without owning map search centering.
 */
export function acquireBrowserPosition(
  options: PositionOptions = DEFAULT_OPTIONS,
): Promise<BrowserGeolocationResult> {
  const geolocation = typeof navigator !== 'undefined' ? navigator.geolocation : undefined;
  if (!geolocation) {
    return Promise.resolve({ ok: false, reason: 'unsupported' });
  }

  return new Promise((resolve) => {
    geolocation.getCurrentPosition(
      (position) => {
        resolve({
          ok: true,
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
        });
      },
      (error) => {
        if (error.code === error.PERMISSION_DENIED) {
          resolve({ ok: false, reason: 'denied' });
          return;
        }
        if (error.code === error.TIMEOUT) {
          resolve({ ok: false, reason: 'timeout' });
          return;
        }
        resolve({ ok: false, reason: 'unavailable' });
      },
      options,
    );
  });
}