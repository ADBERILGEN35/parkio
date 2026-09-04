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

  it('accepts invite-production as a production-like environment', () => {
    const config = createFrontendConfig(
      env({
        VITE_APP_ENV: 'invite-production',
        VITE_API_BASE_URL: 'https://api.parkio.dev/api/v1',
        VITE_MAPTILER_KEY: 'map-key',
      }),
    );

    expect(config.appEnv).toBe('invite-production');
    expect(config.isProductionLike).toBe(true);
    expect(config.features.smartReturn).toBe(false);
  });

  it('requires an API URL for invite-production', () => {
    expect(() =>
      createFrontendConfig(
        env({
          VITE_APP_ENV: 'invite-production',
          VITE_MAPTILER_KEY: 'map-key',
        }),
      ),
    ).toThrow('VITE_API_BASE_URL is required');
  });

  it('requires MapTiler configuration for invite-production', () => {
    expect(() =>
      createFrontendConfig(
        env({
          VITE_APP_ENV: 'invite-production',
          VITE_API_BASE_URL: 'https://api.parkio.dev/api/v1',
        }),
      ),
    ).toThrow('VITE_MAPTILER_KEY is required when VITE_APP_ENV=invite-production');
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

  it('keeps municipal discovery disabled by default', () => {
    const config = createFrontendConfig(env());
    expect(config.features.municipalDiscovery).toBe(false);
  });

  it('enables municipal discovery only when explicitly true', () => {
    const config = createFrontendConfig(
      env({ VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: 'true' }),
    );
    expect(config.features.municipalDiscovery).toBe(true);
  });

  it('keeps municipal discovery disabled in hosted-beta unless explicitly enabled', () => {
    const config = createFrontendConfig(
      env({
        VITE_APP_ENV: 'hosted-beta',
        VITE_API_BASE_URL: 'https://api.parkio.example/api/v1',
        VITE_MAPTILER_KEY: 'map-key',
      }),
    );
    expect(config.features.municipalDiscovery).toBe(false);
  });

  it('keeps municipal discovery disabled when the flag is false', () => {
    const config = createFrontendConfig(
      env({ VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: 'false' }),
    );
    expect(config.features.municipalDiscovery).toBe(false);
  });

  it('keeps smart parking assistant disabled by default', () => {
    const config = createFrontendConfig(env());
    expect(config.features.smartParkingAssistant).toBe(false);
  });

  it('enables smart parking assistant only when explicitly true', () => {
    const config = createFrontendConfig(
      env({ VITE_SMART_PARKING_ASSISTANT_ENABLED: 'true' }),
    );
    expect(config.features.smartParkingAssistant).toBe(true);
  });

  it('keeps smart parking assistant disabled when the flag is false', () => {
    const config = createFrontendConfig(
      env({ VITE_SMART_PARKING_ASSISTANT_ENABLED: 'false' }),
    );
    expect(config.features.smartParkingAssistant).toBe(false);
  });

  it('keeps public explore disabled unless explicitly enabled at build time', () => {
    expect(createFrontendConfig(env()).features.publicExplore).toBe(false);
    expect(createFrontendConfig(env({ VITE_PUBLIC_EXPLORE_ENABLED: 'false' })).features.publicExplore)
      .toBe(false);
    expect(createFrontendConfig(env({ VITE_PUBLIC_EXPLORE_ENABLED: 'true' })).features.publicExplore)
      .toBe(true);
  });

  it('fails registration mode bootstrap closed outside tests and preserves open test UX', () => {
    expect(createFrontendConfig(env()).registrationModeBootstrap).toBe('CLOSED');
    expect(createFrontendConfig(env({ MODE: 'test' })).registrationModeBootstrap).toBe('OPEN');
    expect(createFrontendConfig(env({ VITE_REGISTRATION_MODE: 'invite' })).registrationModeBootstrap)
      .toBe('INVITE');
  });
});
