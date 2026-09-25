import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { en, tr } from '@/i18n/translations';

const ROOT = join(__dirname, '..');
const AUTH = join(__dirname, '../../auth');
const APP_AUTH = join(__dirname, '../../../../app/(auth)');
const SCREEN = readFileSync(join(ROOT, 'PublicExploreScreen.tsx'), 'utf8');
const SUMMARY = readFileSync(join(ROOT, 'PublicExploreSummary.tsx'), 'utf8');
const CONTRIBUTE = readFileSync(join(ROOT, 'PublicExploreContributeCta.tsx'), 'utf8');
const LOGIN = readFileSync(join(APP_AUTH, 'login.tsx'), 'utf8');
const REGISTER = readFileSync(join(APP_AUTH, 'register.tsx'), 'utf8');
const AUTHGATE = readFileSync(join(AUTH, 'AuthGate.tsx'), 'utf8');
const ESCAPE = readFileSync(join(AUTH, 'escapeToPublicExplore.ts'), 'utf8');
const MAP_HTML = readFileSync(join(__dirname, '../../map/mapHtml.ts'), 'utf8');

/**
 * MOBILE-V2-PHYSICAL-UX-FIX-01 — real-device copy / entry UX remediation gates.
 */
describe('MOBILE-V2-PHYSICAL-UX-FIX-01', () => {
  it('A/B: summary uses municipal semantics and renderable visibleCount', () => {
    expect(tr['publicExplore.summary.nearYou']).toContain('belediye otoparkı');
    expect(tr['publicExplore.summary.inArea']).toContain('belediye otoparkı');
    expect(en['publicExplore.summary.nearYou']).toContain('municipal parking facilities');
    expect(SUMMARY).toContain('publicExplore.summary.nearYou');
    expect(SUMMARY).toContain('publicExplore.summary.inArea');
    expect(SUMMARY).not.toMatch(/municipalTotalInScope\s*[=:]/);
    expect(SCREEN).toContain('visibleCount={markers.length}');
  });

  it('C: hiddenCount > 0 uses compact +M on summary with separate press target', () => {
    expect(tr['publicExplore.teaser.municipalHidden']).toBe('+{count} otopark daha');
    expect(en['publicExplore.teaser.municipalHidden']).toBe('+{count} more parking facilities');
    expect(SUMMARY).toContain('municipalHiddenCount');
    expect(SUMMARY).toContain('onMunicipalHiddenPress');
    expect(SCREEN).toContain("openAuthGate('municipal-hidden')");
  });

  it('D: summary never fetch hidden municipal rows anonymously', () => {
    expect(SUMMARY).not.toMatch(/getMunicipal|facilities\/|useQuery|fetch\(/);
  });

  it('E/F: product header is parking-options, not municipal-only', () => {
    expect(tr['publicExplore.subtitle']).toBe('Yakındaki park seçeneklerini keşfet');
    expect(en['publicExplore.subtitle']).toBe('Explore nearby parking options');
    expect(tr['publicExplore.subtitle']).not.toContain('belediye otoparklarını');
    expect(en['publicExplore.subtitle']).not.toMatch(/municipal parking$/i);
  });

  it('G: contribution CTA preserved', () => {
    expect(tr['publicExplore.contribute.cta']).toBe('Park yeri bildir');
    expect(tr['publicExplore.contribute.support']).toBe('Topluluğa katkıda bulun');
    expect(CONTRIBUTE).toContain('publicExplore.contribute.cta');
    expect(SCREEN).toContain('PublicExploreContributeCta');
  });

  it('H: anonymous community precise disclosure remains none', () => {
    expect(SCREEN).toContain('setSpots([])');
    expect(MAP_HTML).toContain('pk-muni-p');
  });

  it('I/J: login title is neutral (no welcome-back assumption)', () => {
    expect(tr['auth.login.title']).toBe("Parkio'ya giriş yap");
    expect(en['auth.login.title']).toBe('Sign in to Parkio');
    expect(tr['auth.login.title']).not.toMatch(/Tekrar|hoş geldin/i);
    expect(en['auth.login.title']).not.toMatch(/Welcome back/i);
  });

  it('K/L/M/N: CLOSED login has explore escape + registration info, no Kayıt ol CTA', () => {
    expect(LOGIN).toContain('auth.login.exploreWithoutAccount');
    expect(LOGIN).toContain('auth.login.registrationAbout');
    expect(LOGIN).toContain("router.push('/(auth)/register')");
    expect(LOGIN).toMatch(/signupAllowed\s*\?/);
    expect(tr['auth.login.registrationAbout']).toBe('Kayıt hakkında bilgi');
    expect(en['auth.login.registrationAbout']).toBe('About registration');
    // OPEN-only signup link key still exists but is gated.
    expect(LOGIN).toContain('auth.login.registerLink');
    expect(tr['auth.login.registerLink']).toBe('Kayıt ol');
  });

  it('O/P/Q/R: CLOSED register screen preserved', () => {
    expect(tr['auth.register.closedTitle']).toBe('Kayıt şu anda kapalı');
    expect(REGISTER).toContain('auth.register.closedTitle');
    expect(REGISTER).toContain('auth.register.exploreLive');
    expect(REGISTER).toContain('escapeToPublicExplore');
    expect(REGISTER).toContain("router.replace('/(auth)/login')");
    const closedIdx = REGISTER.indexOf('auth.register.closedTitle');
    const formIdx = REGISTER.indexOf('auth.register.title');
    const closedSlice = REGISTER.slice(closedIdx, formIdx);
    expect(closedSlice).not.toContain('PasswordChecklist');
    expect(closedSlice).not.toContain('authApi.register');
  });

  it('S: pending contribute intent clears on explore escape', () => {
    expect(ESCAPE).toContain('clearPendingAuthGateIntent');
    expect(ESCAPE).toContain("router.replace('/(public)/explore')");
    expect(LOGIN).toContain('escapeToPublicExplore');
    expect(REGISTER).toContain('escapeToPublicExplore');
  });

  it('T: AuthGate remains truthful (login primary, optional about-registration, no signup)', () => {
    expect(AUTHGATE).toContain('authGate.login');
    expect(AUTHGATE).toContain('authGate.dismiss');
    expect(AUTHGATE).toContain('authGate.registrationAbout');
    expect(AUTHGATE).not.toMatch(/Kayıt ol|Sign up|auth\.login\.registerLink/);
    expect(AUTHGATE).toContain("router.push('/(auth)/register')");
  });

  it('U: public explore does not call private nearby/spot APIs', () => {
    expect(SCREEN).not.toMatch(
      /nearbySpotsQueryOptions|nearbyMunicipalFacilitiesQueryOptions|\/parking\/spots/,
    );
  });

  it('V: W1–W6 contribution + municipal marker wiring preserved', () => {
    expect(SCREEN).toContain('AuthGate');
    expect(SCREEN).toContain('contribute');
    expect(SCREEN).toContain('PUBLIC_EXPLORE_LIMIT');
    expect(MAP_HTML).toContain('pk-muni-p');
  });
});
