import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = join(__dirname, '..');
const SCREEN = readFileSync(join(ROOT, 'PublicExploreScreen.tsx'), 'utf8');
const CTA = readFileSync(join(ROOT, 'PublicExploreContributeCta.tsx'), 'utf8');
const LOGIN = readFileSync(
  join(__dirname, '../../../../app/(auth)/login.tsx'),
  'utf8',
);
const RESOLVE = readFileSync(
  join(__dirname, '../../auth/resolvePostLoginHref.ts'),
  'utf8',
);

describe('Wave 4 contribution discoverability gates', () => {
  it('wires contribute CTA to AuthGate without private share navigation', () => {
    expect(SCREEN).toContain('PublicExploreContributeCta');
    expect(SCREEN).toContain("openAuthGate('contribute')");
    expect(SCREEN).not.toMatch(/\/\(main\)\/share|useCreateSpot|useDraftUpload|createParkingSpot/);
    expect(CTA).not.toMatch(/parkingApi|mediaApi|createParkingSpot|uploadMedia/);
  });

  it('suppresses contribution chrome with discovery chrome during search/sheet', () => {
    expect(SCREEN).toMatch(
      /PublicExploreContributeCta[\s\S]*visible=\{showDiscoveryChrome\}/,
    );
  });

  it('resumes contribute after login into existing share flow', () => {
    expect(LOGIN).toContain('consumePendingAuthGateIntent');
    expect(LOGIN).toContain('resolvePostLoginHref');
    expect(RESOLVE).toContain("'/(main)/share'");
    expect(RESOLVE).toContain("intent === 'contribute'");
  });

  it('does not invent anonymous community precision from contribute CTA', () => {
    expect(CTA).not.toMatch(/latitude|longitude|setSpots|communitySpot/);
    expect(SCREEN).toContain('setSpots([])');
  });
});
