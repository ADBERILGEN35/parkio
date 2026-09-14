import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = join(__dirname, '..');
const SCREEN = readFileSync(join(ROOT, 'PublicExploreScreen.tsx'), 'utf8');
const TEASERS = readFileSync(join(ROOT, 'PublicExploreTeasers.tsx'), 'utf8');
const AUTH_GATE = readFileSync(
  join(__dirname, '../../auth/AuthGate.tsx'),
  'utf8',
);

describe('Wave 3 public membership network + privacy gates', () => {
  it('wires AuthGate for facility detail without private facility detail fetch', () => {
    expect(SCREEN).toContain('onOpenFacilityDetailGate');
    expect(SCREEN).toContain("openAuthGate('facility-detail'");
    expect(SCREEN).not.toMatch(/municipalFacilityDetailQueryOptions|facilities\/\$\{|parking\/facilities\//);
    expect(SCREEN).toContain('parkHereEnabled={false}');
  });

  it('uses server envelope fields for teasers only', () => {
    expect(SCREEN).toContain('municipalHiddenCount');
    expect(SCREEN).toContain('communitySpotCountInScope');
    expect(SCREEN).toContain('PublicExploreTeasers');
    expect(TEASERS).toContain('municipalHiddenCount > 0');
    expect(TEASERS).toContain('communitySpotCountInScope != null');
  });

  it('suppresses teasers while search or preview sheet is active', () => {
    expect(SCREEN).toContain('showDiscoveryChrome');
    expect(SCREEN).toMatch(/showDiscoveryChrome\s*=\s*!sheetOpen\s*&&\s*!searchActive/);
    expect(SCREEN).toContain('visible={showDiscoveryChrome}');
  });

  it('never creates anonymous community markers from aggregate', () => {
    expect(SCREEN).toContain('setSpots([])');
    expect(TEASERS).not.toMatch(/setSpots|latitude|longitude|blue/);
  });

  it('AuthGate omits misleading signup CTA and routes only to login', () => {
    expect(AUTH_GATE).toContain('authGate.login');
    expect(AUTH_GATE).toContain('authGate.dismiss');
    expect(AUTH_GATE).not.toMatch(/authGate\.register|Kayıt ol|Sign up/);
    expect(AUTH_GATE).toContain("router.push('/(auth)/login')");
    expect(AUTH_GATE).not.toMatch(/\/\(auth\)\/register/);
  });

  it('keeps public municipal limit at 6', () => {
    expect(SCREEN).toContain('PUBLIC_EXPLORE_LIMIT');
    const constants = readFileSync(join(ROOT, 'constants.ts'), 'utf8');
    expect(constants).toMatch(/PUBLIC_EXPLORE_LIMIT\s*=\s*6/);
  });

  it('does not call private nearby/spot endpoints from public explore surface', () => {
    expect(SCREEN).not.toMatch(
      /nearbySpotsQueryOptions|nearbyMunicipalFacilitiesQueryOptions|placeSearchQueryOptions|\/parking\/spots/,
    );
  });
});
