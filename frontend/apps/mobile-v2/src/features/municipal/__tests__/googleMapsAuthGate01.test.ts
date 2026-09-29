import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { en, tr } from '@/i18n/translations';

const ROOT = join(__dirname, '..');
const AUTH = join(__dirname, '../../auth');
const MAP = join(__dirname, '../../map');
const PUBLIC = join(__dirname, '../../public-explore');
const APP_AUTH = join(__dirname, '../../../../app/(auth)');
const PKG = join(__dirname, '../../../../package.json');
const SHEET = readFileSync(join(MAP, 'MunicipalFacilitySheet.tsx'), 'utf8');
const DETAIL = readFileSync(join(ROOT, 'MunicipalFacilityDetailScreen.tsx'), 'utf8');
const GMAPS = readFileSync(join(ROOT, 'googleMapsDirections.ts'), 'utf8');
const SCREEN = readFileSync(join(PUBLIC, 'PublicExploreScreen.tsx'), 'utf8');
const LOGIN = readFileSync(join(APP_AUTH, 'login.tsx'), 'utf8');
const INTENTS = readFileSync(join(AUTH, 'authGateIntents.ts'), 'utf8');
const RESUME = readFileSync(join(AUTH, 'resolvePostLoginHref.ts'), 'utf8');
const AUTHGATE = readFileSync(join(AUTH, 'AuthGate.tsx'), 'utf8');
const MAP_HTML = readFileSync(join(MAP, 'mapHtml.ts'), 'utf8');
const PACKAGE_JSON = readFileSync(PKG, 'utf8');

/**
 * MOBILE-V2-GOOGLE-MAPS-AUTH-GATE-01 — authenticated Google Maps handoff.
 */
describe('MOBILE-V2-GOOGLE-MAPS-AUTH-GATE-01', () => {
  it('M/N/O: no Google API key, SDK, or new maps dependency', () => {
    expect(PACKAGE_JSON).not.toMatch(/google-maps|react-native-maps|@react-native-google/i);
    expect(GMAPS).not.toMatch(/EXPO_PUBLIC_GOOGLE|apiKey|API_KEY|Maps SDK/i);
    expect(GMAPS).toContain('www.google.com');
    expect(GMAPS).toContain('/maps/dir/');
    expect(GMAPS).toContain('api=1');
    expect(GMAPS).toContain('destination=');
    expect(GMAPS).not.toContain('origin=');
  });

  it('action copy TR/EN and AuthGate copy', () => {
    expect(tr['map.municipal.openInMaps']).toBe("Google Maps'te aç");
    expect(en['map.municipal.openInMaps']).toBe('Open in Google Maps');
    expect(tr['map.municipal.openInMapsFailed']).toBe('Google Maps açılamadı.');
    expect(en['map.municipal.openInMapsFailed']).toBe("Google Maps couldn't be opened.");
    expect(tr['authGate.googleMaps.title']).toBe("Google Maps'te aç");
    expect(tr['authGate.googleMaps.body']).toContain("Google Maps'te açmak");
    expect(en['authGate.googleMaps.title']).toBe('Open in Google Maps');
  });

  it('B/C: anonymous Google Maps → AuthGate; no Linking before auth on Explore', () => {
    expect(SCREEN).toContain('onRequireAuthForGoogleMaps');
    expect(SCREEN).toContain("openAuthGate('google-maps'");
    expect(SHEET).toContain('onRequireAuthForGoogleMaps');
    expect(SHEET).toMatch(/if \(onRequireAuthForGoogleMaps\)/);
  });

  it('D: authenticated sheet/detail open Google Maps helper', () => {
    expect(SHEET).toContain('openGoogleMapsDirections');
    expect(DETAIL).toContain('openGoogleMapsDirections');
    expect(SHEET).not.toContain('buildParkingMapsHttpsUrl');
    expect(DETAIL).not.toContain('buildParkingMapsHttpsUrl');
    expect(SHEET).not.toContain('openstreetmap.org');
    expect(DETAIL).not.toContain('openstreetmap.org');
  });

  it('P/Q: MapLibre basemap + OSM attribution preserved', () => {
    expect(MAP_HTML).toMatch(/maplibre|MapLibre|maptiler|openstreetmap/i);
    expect(MAP_HTML).toMatch(/attribution|OpenStreetMap/i);
  });

  it('R: facility detail anonymous AuthGate preserved', () => {
    expect(SCREEN).toContain("openAuthGate('facility-detail'");
    expect(DETAIL).toContain("authStatus === 'anonymous'");
    expect(DETAIL).toContain('Redirect href="/(public)/explore"');
  });

  it('S: google-maps auth resume reconstructs from lat/lng; login opens maps', () => {
    expect(INTENTS).toContain("'google-maps'");
    expect(INTENTS).toContain('latitude?: number');
    expect(INTENTS).toContain('longitude?: number');
    expect(RESUME).toContain("'google-maps'");
    expect(LOGIN).toContain("pending?.intent === 'google-maps'");
    expect(LOGIN).toContain('openGoogleMapsDirections');
    expect(LOGIN).not.toMatch(/openURL\(pending/);
  });

  it('U: registration CLOSED remains truthful on AuthGate', () => {
    expect(AUTHGATE).toContain('authGate.registrationAbout');
    expect(AUTHGATE).not.toMatch(/Kayıt ol|Sign up|auth\.login\.registerLink/);
  });

  it('L: never accepts arbitrary external URL for handoff', () => {
    expect(GMAPS).toContain('isTrustedGoogleMapsDirectionsUrl');
    expect(GMAPS).toContain('requireParkingDestination');
    expect(GMAPS).toContain('buildGoogleMapsDirectionsUrl(latitude as number');
    expect(GMAPS).not.toMatch(/openURL\(\s*(pending|user|arbitrary)/);
  });
});
