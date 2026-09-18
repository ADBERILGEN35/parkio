import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { en, tr } from '@/i18n/translations';

const ROOT = join(__dirname, '..');
const AUTH = join(__dirname, '../../auth');
const MUNICIPAL = join(__dirname, '../../municipal');
const APP_AUTH = join(__dirname, '../../../../app/(auth)');
const SCREEN = readFileSync(join(ROOT, 'PublicExploreScreen.tsx'), 'utf8');
const SUMMARY = readFileSync(join(ROOT, 'PublicExploreSummary.tsx'), 'utf8');
const DETAIL = readFileSync(join(MUNICIPAL, 'MunicipalFacilityDetailScreen.tsx'), 'utf8');
const RESUME = readFileSync(join(AUTH, 'resolvePostLoginHref.ts'), 'utf8');
const LOGIN = readFileSync(join(APP_AUTH, 'login.tsx'), 'utf8');
const AUTHGATE = readFileSync(join(AUTH, 'AuthGate.tsx'), 'utf8');
const MAP_HTML = readFileSync(join(__dirname, '../../map/mapHtml.ts'), 'utf8');

/**
 * MOBILE-V2-PHYSICAL-UX-FIX-02 — contextual discovery + facility auth boundary.
 */
describe('MOBILE-V2-PHYSICAL-UX-FIX-02', () => {
  it('A–D: contextual summary keys and origin wiring', () => {
    expect(tr['publicExplore.summary.nearYou']).toBe('Konumunuza yakın {count} belediye otoparkı');
    expect(en['publicExplore.summary.nearYou']).toBe('{count} municipal parking facilities near you');
    expect(tr['publicExplore.summary.inArea']).toBe('Bu bölgede {count} belediye otoparkı');
    expect(en['publicExplore.summary.inArea']).toBe(
      '{count} municipal parking facilities in this area',
    );
    expect(SUMMARY).toContain("origin === 'user-location'");
    expect(SCREEN).toContain("selectedDestination == null && userLocation != null");
    expect(SCREEN).toContain("origin={summaryOrigin}");
  });

  it('E: visible count uses renderable markers, not municipalTotalInScope', () => {
    expect(SCREEN).toContain('visibleCount={markers.length}');
    expect(SUMMARY).not.toMatch(/municipalTotalInScope\s*[=:]/);
  });

  it('F/G: community supporting copy keys; null stays absent', () => {
    expect(tr['publicExplore.summary.communitySupport']).toBe(
      '{count} topluluk park noktası da mevcut',
    );
    expect(en['publicExplore.summary.communitySupport']).toBe(
      '{count} community-reported parking spots are also available',
    );
    expect(SUMMARY).toContain('communitySpotCountInScope != null');
    expect(SCREEN).toContain('communitySpotCountInScope={communitySpotCountInScope}');
  });

  it('H: anonymous community precise markers remain none', () => {
    expect(SCREEN).toContain('setSpots([])');
    expect(MAP_HTML).toContain('pk-muni-p');
  });

  it('I/J: hidden +N copy and AuthGate wiring', () => {
    expect(tr['publicExplore.teaser.municipalHidden']).toBe('+{count} otopark daha');
    expect(en['publicExplore.teaser.municipalHidden']).toBe('+{count} more parking facilities');
    expect(SCREEN).toContain("openAuthGate('municipal-hidden')");
  });

  it('K–N: Tesis detayını gör → AuthGate only; no private navigate/fetch on Explore', () => {
    expect(SCREEN).toContain('onOpenFacilityDetailGate');
    expect(SCREEN).toContain("openAuthGate('facility-detail'");
    expect(SCREEN).not.toMatch(/router\.(push|replace).*facilities/);
    expect(SCREEN).not.toMatch(/municipalFacilityDetailQueryOptions|getMunicipalFacility/);
  });

  it('O: detail screen redirects anonymous and disables private query', () => {
    expect(DETAIL).toContain("authStatus === 'anonymous'");
    expect(DETAIL).toContain("Redirect href=\"/(public)/explore\"");
    expect(DETAIL).toContain('authenticated && municipalDiscovery && facilityId.length > 0');
  });

  it('P/Q: authenticated detail path preserved', () => {
    expect(DETAIL).toContain('municipalFacilityDetailQueryOptions');
    expect(DETAIL).toContain("authStatus === 'authenticated'");
  });

  it('R: facility AuthGate copy + CLOSED registration info remain truthful', () => {
    expect(tr['authGate.facilityDetail.title']).toBe('Parkio ile daha fazlasını keşfedin');
    expect(tr['authGate.facilityDetail.body']).toBe(
      'Tesisin tüm detaylarını görmek için giriş yapın.',
    );
    expect(en['authGate.facilityDetail.title']).toBe('Discover more with Parkio');
    expect(AUTHGATE).toContain('authGate.registrationAbout');
    expect(AUTHGATE).not.toMatch(/Kayıt ol|Sign up|auth\.login\.registerLink/);
    expect(LOGIN).toContain('auth.login.registrationAbout');
  });

  it('S–V: search hierarchy, contribute, destination, location chrome preserved', () => {
    expect(SCREEN).toMatch(/showDiscoveryChrome\s*=\s*!sheetOpen\s*&&\s*!searchActive/);
    expect(SCREEN).toContain('PublicExploreContributeCta');
    expect(SCREEN).toContain('PublicMapSearchOverlay');
    expect(SCREEN).toContain('selectedDestination');
    expect(SCREEN).toContain('useLocation');
  });

  it('W: green municipal marker semantics preserved', () => {
    expect(MAP_HTML).toContain('pk-muni-p');
    expect(SCREEN).toContain('toMapMunicipalMarkers');
  });

  it('facility auth resume uses public facility id only', () => {
    expect(RESUME).toContain("intent === 'facility-detail'");
    // Expo Router object href so useLocalSearchParams().id is populated.
    expect(RESUME).toContain("pathname: '/(main)/facilities/[id]'");
    expect(RESUME).toContain('params: { id: facilityId }');
    expect(RESUME).toContain('/(main)/facilities/');
  });
});
