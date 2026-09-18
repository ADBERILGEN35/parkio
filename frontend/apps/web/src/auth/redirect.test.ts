import { describe, expect, it } from 'vitest';
import {
  ROUTE_IDS,
  ROUTE_MANIFEST,
  buildRoutePath,
  getRoutePath,
} from '@/routing/route-manifest';
import {
  AUTH_RETURN_QUERY_PARAM,
  createSanitizedLoginReturnSearch,
  sanitizeInternalRedirect,
} from './redirect';

const validUuid = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';

describe('sanitizeInternalRedirect', () => {
  it.each([
    ['/profile', '/profile'],
    [{ pathname: '/notifications', search: '?page=2' }, '/notifications?page=2'],
    ['/spots/6f9619ff-8b86-4d01-b42d-00cf4fc964ff?from=map', '/spots/6f9619ff-8b86-4d01-b42d-00cf4fc964ff?from=map'],
    [{ pathname: '/admin/users/6f9619ff-8b86-4d01-b42d-00cf4fc964ff' }, '/admin/users/6f9619ff-8b86-4d01-b42d-00cf4fc964ff'],
    ['/map/', '/map'],
    [
      {
        pathname: '/map',
        search: '?communityLayer=0&municipalSources=Izmir+Buyuksehir+Belediyesi+%2F+IZUM',
      },
      '/map?communityLayer=0&municipalSources=Izmir+Buyuksehir+Belediyesi+%2F+IZUM',
    ],
  ])('accepts recognized internal route %#', (value, expected) => {
    expect(sanitizeInternalRedirect(value)).toBe(expected);
  });

  it.each([
    [{ pathname: '/profile', search: 'page=2' }, '/profile'],
    [{ pathname: '/profile', search: '?page=2#bad' }, '/profile'],
    ['/profile?tab=security#fragment', '/profile?tab=security'],
    [{ pathname: '/profile', search: '?\u0000bad=1' }, '/profile'],
  ])('preserves only safe search content %#', (value, expected) => {
    expect(sanitizeInternalRedirect(value)).toBe(expected);
  });

  it.each([
    'https://evil.example/steal',
    '//evil.example/steal',
    'javascript:alert(1)',
    'data:text/html,boom',
    'file:///etc/passwd',
    '/unknown',
    '/..//admin',
    '/\\evil',
    '/login',
    '/spots/not-a-uuid',
    { pathname: 'https://evil.example' },
    { pathname: '/\\evil' },
    null,
    undefined,
  ])('falls back for unrecognized redirect %#', (value) => {
    expect(sanitizeInternalRedirect(value)).toBe('/map');
  });

  it('returns a valid candidate when both candidate and fallback are recognized', () => {
    expect(sanitizeInternalRedirect('/profile', '/notifications')).toBe('/profile');
  });

  it('uses a recognized fallback when the candidate is invalid', () => {
    expect(sanitizeInternalRedirect('/unknown', '/notifications')).toBe('/notifications');
  });

  it.each([
    'https://evil.example/steal',
    '//evil.example/steal',
    '%2F%2Fevil.example%2Fsteal',
    '%252F%252Fevil.example%252Fsteal',
    '/unknown',
    '/%E0%A4%A',
    '\u0000/map',
  ])('keeps a valid candidate when fallback %# is unsafe', (fallback) => {
    expect(sanitizeInternalRedirect('/profile', fallback)).toBe('/profile');
  });

  it.each([
    ['https://evil.example/steal', 'https://fallback.example/steal'],
    ['//evil.example/steal', '//fallback.example/steal'],
    ['%2F%2Fevil.example%2Fsteal', '%2F%2Ffallback.example%2Fsteal'],
    ['%252F%252Fevil.example%252Fsteal', '%252F%252Ffallback.example%252Fsteal'],
    ['/unknown', '/also-unknown'],
    ['/%E0%A4%A', '/%C3%28'],
    ['\u0000/map', '\u0000/profile'],
  ])('uses the deterministic safe default for invalid candidate %# and fallback %#', (
    candidate,
    fallback,
  ) => {
    expect(sanitizeInternalRedirect(candidate, fallback)).toBe('/map');
  });

  it('uses the deterministic safe default when both inputs are absent', () => {
    expect(sanitizeInternalRedirect(undefined, undefined)).toBe(
      getRoutePath(ROUTE_IDS.MAP),
    );
  });

  it.each([
    '/map?communityLayer=0&municipalAvailability=available',
    { pathname: '/profile', search: '?tab=security&foo=' },
  ])('is idempotent for sanitized redirect %#', (value) => {
    const sanitized = sanitizeInternalRedirect(value);
    expect(sanitizeInternalRedirect(sanitized)).toBe(sanitized);
  });

  it('creates an encoded login return query for sanitized internal targets', () => {
    const search = createSanitizedLoginReturnSearch(
      '/map?smartReturn=1&communityLayer=0&municipalAvailability=available&foo=&foo=bar',
    );
    const params = new URLSearchParams(search.slice(1));
    expect(params.get(AUTH_RETURN_QUERY_PARAM)).toBe(
      '/map?smartReturn=1&communityLayer=0&municipalAvailability=available&foo=&foo=bar',
    );
  });

  it('uses the same deterministic fallback for unsafe login return targets', () => {
    const search = createSanitizedLoginReturnSearch('//evil.example/steal');
    const params = new URLSearchParams(search.slice(1));
    expect(params.get(AUTH_RETURN_QUERY_PARAM)).toBe('/map');
  });

  it('derives the complete recognized redirect set from manifest eligibility', () => {
    for (const entry of ROUTE_MANIFEST.filter(
      (candidate) => candidate.redirectEligible,
    )) {
      const parameters = Object.fromEntries(
        entry.parameters.map((parameter) => [
          parameter.name,
          validUuid,
        ]),
      );
      const path = buildRoutePath(entry.id, parameters);

      expect(sanitizeInternalRedirect(path)).toBe(path);
    }

    for (const entry of ROUTE_MANIFEST.filter(
      (candidate) =>
        candidate.access === 'public' &&
        candidate.kind === 'path' &&
        candidate.path !== '*',
    )) {
      expect(sanitizeInternalRedirect(getRoutePath(entry.id))).toBe(
        getRoutePath(ROUTE_IDS.MAP),
      );
    }
  });
});
