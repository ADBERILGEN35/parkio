import {
  clearPendingAuthGateIntent,
  setPendingAuthGateIntent,
} from '../authGateIntents';
import { resolvePostLoginHref } from '../resolvePostLoginHref';

describe('resolvePostLoginHref', () => {
  afterEach(() => {
    clearPendingAuthGateIntent();
  });

  it('resumes contribute intent into authenticated share wizard', () => {
    expect(resolvePostLoginHref({ intent: 'contribute' })).toBe('/(main)/share');
  });

  it('resumes facility-detail with public facility id into authenticated detail', () => {
    expect(resolvePostLoginHref({ intent: 'facility-detail', facilityId: 'fac-1' })).toEqual({
      pathname: '/(main)/facilities/[id]',
      params: { id: 'fac-1' },
    });
  });

  it('resumes google-maps with public facility id into authenticated detail', () => {
    expect(
      resolvePostLoginHref({
        intent: 'google-maps',
        facilityId: 'fac-gm',
        latitude: 38.4,
        longitude: 27.1,
      }),
    ).toEqual({
      pathname: '/(main)/facilities/[id]',
      params: { id: 'fac-gm' },
    });
  });

  it('falls back to map when facility-detail lacks id or for other intents', () => {
    expect(resolvePostLoginHref({ intent: 'facility-detail' })).toBe('/(main)/(tabs)/map');
    expect(resolvePostLoginHref({ intent: 'google-maps' })).toBe('/(main)/(tabs)/map');
    expect(resolvePostLoginHref({ intent: 'municipal-hidden' })).toBe('/(main)/(tabs)/map');
    expect(resolvePostLoginHref({ intent: 'community-teaser' })).toBe('/(main)/(tabs)/map');
    expect(resolvePostLoginHref(null)).toBe('/(main)/(tabs)/map');
  });

  it('pairs with pending contribute intent storage', () => {
    setPendingAuthGateIntent({ intent: 'contribute' });
    expect(resolvePostLoginHref({ intent: 'contribute' })).toBe('/(main)/share');
  });
});
