import { createMobileConfig } from '../env';

const baseEnv = {
  EXPO_PUBLIC_APP_ENV: 'development',
  EXPO_PUBLIC_API_BASE_URL: 'http://localhost:8080/api/v1',
} as const;

describe('createMobileConfig municipalDiscovery flag', () => {
  it('accepts explicit true', () => {
    const config = createMobileConfig({
      ...baseEnv,
      EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED: 'true',
    });
    expect(config.features.municipalDiscovery).toBe(true);
  });

  it('accepts explicit false', () => {
    const config = createMobileConfig({
      ...baseEnv,
      EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED: 'false',
    });
    expect(config.features.municipalDiscovery).toBe(false);
  });

  it('defaults to false when missing in development', () => {
    const config = createMobileConfig({ ...baseEnv });
    expect(config.features.municipalDiscovery).toBe(false);
  });

  it('defaults to false when missing in production-like environments', () => {
    for (const appEnv of ['hosted-beta', 'production'] as const) {
      const config = createMobileConfig({
        EXPO_PUBLIC_APP_ENV: appEnv,
        EXPO_PUBLIC_API_BASE_URL: 'https://api.parkio.dev/api/v1',
      });
      expect(config.features.municipalDiscovery).toBe(false);
      expect(config.isProductionLike).toBe(true);
    }
  });

  it('treats malformed values as disabled (safe default)', () => {
    const config = createMobileConfig({
      ...baseEnv,
      EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED: 'yes',
    });
    expect(config.features.municipalDiscovery).toBe(false);
  });

  it('treats blank values as disabled', () => {
    const config = createMobileConfig({
      ...baseEnv,
      EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED: '   ',
    });
    expect(config.features.municipalDiscovery).toBe(false);
  });

  it('supports explicit enablement in production-like profiles', () => {
    const config = createMobileConfig({
      EXPO_PUBLIC_APP_ENV: 'hosted-beta',
      EXPO_PUBLIC_API_BASE_URL: 'https://api.parkio.dev/api/v1',
      EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED: 'true',
    });
    expect(config.features.municipalDiscovery).toBe(true);
  });
});
