import easConfig from '../../eas.json';
import { createMobileConfig } from './env';

describe('mobile environment configuration', () => {
  it('uses the canonical API hostname for hosted-beta EAS builds', () => {
    const preview = easConfig.build.preview.env;

    expect(preview).toMatchObject({
      EXPO_PUBLIC_APP_ENV: 'hosted-beta',
      EXPO_PUBLIC_API_BASE_URL: 'https://api.parkio.dev/api/v1',
    });
    expect(preview.EXPO_PUBLIC_API_BASE_URL).not.toContain('beta-api.parkio.dev');
  });

  it('requires an explicit API endpoint even for local development', () => {
    expect(() => createMobileConfig({})).toThrow('EXPO_PUBLIC_API_BASE_URL is required');
  });

  it('requires an explicit API URL for production-like environments', () => {
    expect(() => createMobileConfig({ EXPO_PUBLIC_APP_ENV: 'hosted-beta' })).toThrow(
      'EXPO_PUBLIC_API_BASE_URL is required',
    );
  });
});
