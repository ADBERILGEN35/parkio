import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { en, tr } from '@/i18n/translations';

const AUTH = join(__dirname, '..');
const APP_AUTH = join(__dirname, '../../../../app/(auth)');
const ONBOARDING = join(__dirname, '../../../../app/(onboarding)');
const PUBLIC_EXPLORE = join(__dirname, '../../public-explore');

const LOGIN = readFileSync(join(APP_AUTH, 'login.tsx'), 'utf8');
const REGISTER = readFileSync(join(APP_AUTH, 'register.tsx'), 'utf8');
const WELCOME = readFileSync(join(ONBOARDING, 'welcome.tsx'), 'utf8');
const AUTHGATE = readFileSync(join(AUTH, 'AuthGate.tsx'), 'utf8');
const HOOK = readFileSync(join(AUTH, 'useRegistrationMode.ts'), 'utf8');
const ESCAPE = readFileSync(join(AUTH, 'escapeToPublicExplore.ts'), 'utf8');
const SCREEN = readFileSync(join(PUBLIC_EXPLORE, 'PublicExploreScreen.tsx'), 'utf8');
const MAP_HTML = readFileSync(join(__dirname, '../../map/mapHtml.ts'), 'utf8');

describe('MOBILE-V2-PUBLIC-DISCOVERY-W6 registration closed + public entry', () => {
  it('reuses authoritative GET /auth/registration-mode via authApi', () => {
    expect(HOOK).toContain('getRegistrationMode');
    expect(HOOK).toContain("'CLOSED'");
    expect(HOOK).toContain('REGISTRATION_MODES');
  });

  it('CLOSED login has no unconditional Kayıt ol CTA', () => {
    expect(LOGIN).toContain('isRegistrationSignupAllowed');
    expect(LOGIN).toContain('signupAllowed');
    expect(LOGIN).toMatch(/signupAllowed\s*\?/);
    expect(LOGIN).toContain('auth.login.registerLink');
  });

  it('login shows explore-without-account escape to public Explore', () => {
    expect(LOGIN).toContain('auth.login.exploreWithoutAccount');
    expect(LOGIN).toContain('escapeToPublicExplore');
    expect(ESCAPE).toContain("router.replace('/(public)/explore')");
    expect(ESCAPE).toContain('clearPendingAuthGateIntent');
  });

  it('CLOSED register shows dedicated closed state without form fields', () => {
    expect(REGISTER).toContain('auth.register.closedTitle');
    expect(REGISTER).toContain('auth.register.closedBody');
    expect(REGISTER).toContain('auth.register.exploreLive');
    expect(REGISTER).toContain('if (!signupAllowed)');
    expect(REGISTER).toContain('PasswordChecklist');
    // Closed branch must not call register before the OPEN form return.
    const closedIdx = REGISTER.indexOf('auth.register.closedTitle');
    const formIdx = REGISTER.indexOf('auth.register.title');
    expect(closedIdx).toBeGreaterThan(-1);
    expect(formIdx).toBeGreaterThan(closedIdx);
    const closedSlice = REGISTER.slice(closedIdx, formIdx);
    expect(closedSlice).not.toContain('PasswordChecklist');
    expect(closedSlice).not.toContain('authApi.register');
  });

  it('CLOSED register hard-gates submit (no POST when closed)', () => {
    expect(REGISTER).toContain('if (!isRegistrationSignupAllowed(registrationMode))');
    expect(REGISTER).toContain('authApi.register');
  });

  it('OPEN registration form path is preserved', () => {
    expect(REGISTER).toContain('auth.register.title');
    expect(REGISTER).toContain('PasswordChecklist');
    expect(REGISTER).toContain('authApi.register');
    expect(REGISTER).toContain('stashPendingProfile');
  });

  it('AuthGate has no misleading signup CTA', () => {
    expect(AUTHGATE).not.toContain('register');
    expect(AUTHGATE).not.toContain('Kayıt');
    expect(AUTHGATE).toContain('authGate.login');
    expect(AUTHGATE).toContain('authGate.dismiss');
  });

  it('welcome hides register CTA while CLOSED and removes beta chip', () => {
    expect(WELCOME).toContain('isRegistrationSignupAllowed');
    expect(WELCOME).toContain('signupAllowed');
    expect(WELCOME).not.toContain('profile.about.stage');
    expect(WELCOME).not.toContain('betaChip');
    expect(WELCOME).toContain("router.replace('/(public)/explore')");
  });

  it('TR/EN closed registration + explore escape copy', () => {
    expect(tr['auth.login.exploreWithoutAccount']).toBe('Hesap olmadan keşfet');
    expect(en['auth.login.exploreWithoutAccount']).toBe('Explore without an account');
    expect(tr['auth.register.closedTitle']).toBe('Kayıt şu anda kapalı');
    expect(en['auth.register.closedTitle']).toBe('Registration is currently closed');
    expect(tr['auth.register.closedBody']).toContain('hesap oluşturmadan');
    expect(tr['auth.register.closedBody']).toContain('yakında açılacak');
    expect(en['auth.register.closedBody']).toContain('without creating an account');
    expect(tr['auth.register.exploreLive']).toBe("Canlı Parkio'yu keşfet");
    expect(en['auth.register.exploreLive']).toBe('Explore live Parkio');
    expect(tr['auth.register.loginLink']).toBe('Giriş yap');
  });

  it('closed copy avoids beta / salt-okunur / read-only framing', () => {
    const closedKeys = [
      tr['auth.register.closedTitle'],
      tr['auth.register.closedBody'],
      tr['auth.register.exploreLive'],
      en['auth.register.closedTitle'],
      en['auth.register.closedBody'],
      en['auth.register.exploreLive'],
      tr['auth.login.exploreWithoutAccount'],
      en['auth.login.exploreWithoutAccount'],
    ].join(' ');
    expect(closedKeys.toLowerCase()).not.toMatch(/beta|pilot|demo|salt okunur|read-only|readonly|invite only|early access/);
  });

  it('preserves contribute AuthGate wiring on Public Explore', () => {
    expect(SCREEN).toContain('contribute');
    expect(SCREEN).toContain('AuthGate');
  });

  it('preserves W5 marker semantics and anonymous community privacy', () => {
    expect(MAP_HTML).toContain('pk-muni-p');
    expect(MAP_HTML).toContain('pk-spot-p');
    expect(SCREEN).toContain('setSpots([])');
  });
});
