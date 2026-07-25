import { afterEach, describe, expect, it } from 'vitest';
import { acquireBrowserPosition } from './acquireBrowserPosition';

afterEach(() => {
  Object.defineProperty(navigator, 'geolocation', { configurable: true, value: undefined });
});

describe('acquireBrowserPosition', () => {
  it('returns unsupported when Geolocation API is missing', async () => {
    Object.defineProperty(navigator, 'geolocation', { configurable: true, value: undefined });
    await expect(acquireBrowserPosition()).resolves.toEqual({ ok: false, reason: 'unsupported' });
  });

  it('returns coordinates on success', async () => {
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (success: PositionCallback) => {
          success({
            coords: { latitude: 38.42, longitude: 27.14 },
          } as GeolocationPosition);
        },
      },
    });

    await expect(acquireBrowserPosition()).resolves.toEqual({
      ok: true,
      latitude: 38.42,
      longitude: 27.14,
    });
  });

  it('maps PERMISSION_DENIED', async () => {
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (_s: PositionCallback, error: PositionErrorCallback) => {
          error({
            code: 1,
            PERMISSION_DENIED: 1,
            POSITION_UNAVAILABLE: 2,
            TIMEOUT: 3,
            message: 'denied',
          } as GeolocationPositionError);
        },
      },
    });

    await expect(acquireBrowserPosition()).resolves.toEqual({ ok: false, reason: 'denied' });
  });

  it('maps TIMEOUT', async () => {
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (_s: PositionCallback, error: PositionErrorCallback) => {
          error({
            code: 3,
            PERMISSION_DENIED: 1,
            POSITION_UNAVAILABLE: 2,
            TIMEOUT: 3,
            message: 'timeout',
          } as GeolocationPositionError);
        },
      },
    });

    await expect(acquireBrowserPosition()).resolves.toEqual({ ok: false, reason: 'timeout' });
  });

  it('maps POSITION_UNAVAILABLE', async () => {
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (_s: PositionCallback, error: PositionErrorCallback) => {
          error({
            code: 2,
            PERMISSION_DENIED: 1,
            POSITION_UNAVAILABLE: 2,
            TIMEOUT: 3,
            message: 'unavailable',
          } as GeolocationPositionError);
        },
      },
    });

    await expect(acquireBrowserPosition()).resolves.toEqual({ ok: false, reason: 'unavailable' });
  });
});