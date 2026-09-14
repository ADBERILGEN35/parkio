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

  it('falls back to map for other AuthGate intents', () => {
    expect(resolvePostLoginHref({ intent: 'facility-detail', facilityId: 'f1' })).toBe(
      '/(main)/(tabs)/map',
    );
    expect(resolvePostLoginHref({ intent: 'municipal-hidden' })).toBe('/(main)/(tabs)/map');
    expect(resolvePostLoginHref({ intent: 'community-teaser' })).toBe('/(main)/(tabs)/map');
    expect(resolvePostLoginHref(null)).toBe('/(main)/(tabs)/map');
  });

  it('pairs with pending contribute intent storage', () => {
    setPendingAuthGateIntent({ intent: 'contribute' });
    expect(resolvePostLoginHref({ intent: 'contribute' })).toBe('/(main)/share');
  });
});
