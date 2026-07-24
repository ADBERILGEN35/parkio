import { describe, expect, it } from 'vitest';
import {
  ROUTE_IDS,
  ROUTE_MANIFEST,
  buildRoutePath,
  getRoutePath,
} from '@/routing/route-manifest';
import { sanitizeInternalRedirect } from './redirect';

const validUuid = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';

describe('sanitizeInternalRedirect', () => {
  it.each([
    ['/profile', '/profile'],
    [{ pathname: '/notifications', search: '?page=2' }, '/notifications'],
    ['/spots/6f9619ff-8b86-4d01-b42d-00cf4fc964ff?from=map', '/spots/6f9619ff-8b86-4d01-b42d-00cf4fc964ff'],
    [{ pathname: '/admin/users/6f9619ff-8b86-4d01-b42d-00cf4fc964ff' }, '/admin/users/6f9619ff-8b86-4d01-b42d-00cf4fc964ff'],
    ['/map/', '/map'],
  ])('accepts recognized internal route %#', (value, expected) => {
    expect(sanitizeInternalRedirect(value)).toBe(expected);
  });

  it.each([
    'https://evil.example/steal',
    '//evil.example/steal',
    'javascript:alert(1)',
    '/unknown',
    '/login',
    '/spots/not-a-uuid',
    { pathname: 'https://evil.example' },
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
