import {
  clearPendingAuthGateIntent,
  peekPendingAuthGateIntent,
  setPendingAuthGateIntent,
} from '../authGateIntents';
import { escapeToPublicExplore } from '../escapeToPublicExplore';
import { isRegistrationSignupAllowed } from '../useRegistrationMode';
import { resolvePostLoginHref } from '../resolvePostLoginHref';

describe('isRegistrationSignupAllowed', () => {
  it('blocks CLOSED and allows OPEN/INVITE', () => {
    expect(isRegistrationSignupAllowed('CLOSED')).toBe(false);
    expect(isRegistrationSignupAllowed('OPEN')).toBe(true);
    expect(isRegistrationSignupAllowed('INVITE')).toBe(true);
  });
});

describe('escapeToPublicExplore', () => {
  afterEach(() => {
    clearPendingAuthGateIntent();
  });

  it('replaces to public Explore and clears pending contribute intent', () => {
    setPendingAuthGateIntent({ intent: 'contribute' });
    expect(peekPendingAuthGateIntent()?.intent).toBe('contribute');

    const replace = jest.fn();
    escapeToPublicExplore({ replace });

    expect(replace).toHaveBeenCalledWith('/(public)/explore');
    expect(peekPendingAuthGateIntent()).toBeNull();
  });

  it('clears pending google-maps intent on Hesap olmadan keşfet', () => {
    setPendingAuthGateIntent({
      intent: 'google-maps',
      facilityId: 'fac-gm',
      latitude: 38.4,
      longitude: 27.1,
    });
    escapeToPublicExplore({ replace: jest.fn() });
    expect(peekPendingAuthGateIntent()).toBeNull();
  });

  it('prevents stale contribute resume after explore escape', () => {
    setPendingAuthGateIntent({ intent: 'contribute' });
    escapeToPublicExplore({ replace: jest.fn() });

    // Simulate a later ordinary login with no pending intent.
    expect(resolvePostLoginHref(peekPendingAuthGateIntent())).toBe('/(main)/(tabs)/map');
    expect(resolvePostLoginHref(null)).not.toBe('/(main)/share');
  });
});
