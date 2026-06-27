import { describe, expect, it } from 'vitest';
import { createFrontendConfig } from './env';

function env(overrides: Record<string, unknown> = {}): ImportMetaEnv {
  return {
    MODE: 'development',
    DEV: true,
    PROD: false,
    BASE_URL: '/',
    SSR: false,
    ...overrides,
  } as ImportMetaEnv;
}

describe('createFrontendConfig', () => {
  it('uses safe development defaults', () => {
    const config = createFrontendConfig(env());

    expect(config.appEnv).toBe('development');
    expect(config.apiBaseUrl).toBe('http://localhost:8080/api/v1');
    expect(config.map.maptilerKey).toBe('');
    expect(config.features.smartReturn).toBe(true);
  });

  it('fails production when VITE_API_BASE_URL is missing', () => {
    expect(() =>
      createFrontendConfig(env({ PROD: true, DEV: false, MODE: 'production' })),
    ).toThrow('VITE_API_BASE_URL is required');
  });

  it('fails on an invalid API URL', () => {
    expect(() => createFrontendConfig(env({ VITE_API_BASE_URL: 'not-a-url' }))).toThrow();
  });

  it('reads map config from validated env', () => {
    const config = createFrontendConfig(
      env({
        VITE_API_BASE_URL: 'https://api.parkio.example/api/v1',
        VITE_MAPTILER_KEY: 'map-key',
        VITE_MAPTILER_STYLE: 'streets-v2-dark',
        VITE_MAP_TILE_URL: 'https://tiles.example/{z}/{x}/{y}.png',
        VITE_MAP_TILE_ATTRIBUTION: 'Example tiles',
      }),
    );

    expect(config.map).toEqual({
      maptilerKey: 'map-key',
      maptilerStyle: 'streets-v2-dark',
      rasterTileUrl: 'https://tiles.example/{z}/{x}/{y}.png',
      rasterAttribution: 'Example tiles',
    });
  });

  it('requires production MapTiler configuration', () => {
    expect(() =>
      createFrontendConfig(
        env({
          VITE_APP_ENV: 'hosted-beta',
          VITE_API_BASE_URL: 'https://api.parkio.example/api/v1',
        }),
      ),
    ).toThrow('VITE_MAPTILER_KEY is required');
  });

  it('keeps Smart Return disabled in hosted-beta unless explicitly enabled', () => {
    const config = createFrontendConfig(
      env({
        VITE_APP_ENV: 'hosted-beta',
        VITE_API_BASE_URL: 'https://api.parkio.example/api/v1',
        VITE_MAPTILER_KEY: 'map-key',
      }),
    );

    expect(config.features.smartReturn).toBe(false);
  });

  it('enables Smart Return when the frontend flag is true', () => {
    const config = createFrontendConfig(env({ VITE_SMART_RETURN_ENABLED: 'true' }));

    expect(config.features.smartReturn).toBe(true);
  });

  it('disables Smart Return when the frontend flag is false', () => {
    const config = createFrontendConfig(env({ VITE_SMART_RETURN_ENABLED: 'false' }));

    expect(config.features.smartReturn).toBe(false);
  });
});
