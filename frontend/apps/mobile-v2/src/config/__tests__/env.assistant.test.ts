import { createMobileConfig } from '../env';

const baseEnv = {
  EXPO_PUBLIC_APP_ENV: 'development',
  EXPO_PUBLIC_API_BASE_URL: 'http://localhost:8080/api/v1',
} as const;

describe('createMobileConfig smartParkingAssistant flag', () => {
  it('accepts explicit true', () => {
    const config = createMobileConfig({
      ...baseEnv,
      EXPO_PUBLIC_SMART_PARKING_ASSISTANT_ENABLED: 'true',
    });
    expect(config.features.smartParkingAssistant).toBe(true);
  });

  it('accepts explicit false', () => {
    const config = createMobileConfig({
      ...baseEnv,
      EXPO_PUBLIC_SMART_PARKING_ASSISTANT_ENABLED: 'false',
    });
    expect(config.features.smartParkingAssistant).toBe(false);
  });

  it('defaults to false when missing', () => {
    const config = createMobileConfig({ ...baseEnv });
    expect(config.features.smartParkingAssistant).toBe(false);
  });

  it('treats malformed values as disabled', () => {
    const config = createMobileConfig({
      ...baseEnv,
      EXPO_PUBLIC_SMART_PARKING_ASSISTANT_ENABLED: 'yes',
    });
    expect(config.features.smartParkingAssistant).toBe(false);
  });

  it('is independent of municipalDiscovery', () => {
    const spaOnMuniOff = createMobileConfig({
      ...baseEnv,
      EXPO_PUBLIC_SMART_PARKING_ASSISTANT_ENABLED: 'true',
      EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED: 'false',
    });
    expect(spaOnMuniOff.features.smartParkingAssistant).toBe(true);
    expect(spaOnMuniOff.features.municipalDiscovery).toBe(false);

    const spaOffMuniOn = createMobileConfig({
      ...baseEnv,
      EXPO_PUBLIC_SMART_PARKING_ASSISTANT_ENABLED: 'false',
      EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED: 'true',
    });
    expect(spaOffMuniOn.features.smartParkingAssistant).toBe(false);
    expect(spaOffMuniOn.features.municipalDiscovery).toBe(true);
  });
});
