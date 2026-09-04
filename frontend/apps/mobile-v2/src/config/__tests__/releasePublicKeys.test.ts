import fs from 'node:fs';
import path from 'node:path';

/**
 * MOBILE-V2-RELEASE-01: release `.env` materializer must inline feature flags.
 */
describe('run-android-release PUBLIC_KEYS', () => {
  it('includes municipal and SPA flags for Expo release inlining', () => {
    const src = fs.readFileSync(
      path.join(__dirname, '../../../scripts/run-android-release.mjs'),
      'utf8',
    );
    expect(src).toContain("'EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED'");
    expect(src).toContain("'EXPO_PUBLIC_SMART_PARKING_ASSISTANT_ENABLED'");
    expect(src).toContain("'EXPO_PUBLIC_API_BASE_URL'");
  });
});
