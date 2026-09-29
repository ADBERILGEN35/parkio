import {
  authGateBodyKey,
  authGateTitleKey,
  clearPendingAuthGateIntent,
  consumePendingAuthGateIntent,
  peekPendingAuthGateIntent,
  setPendingAuthGateIntent,
  type AuthGateIntent,
} from '../authGateIntents';

describe('authGateIntents', () => {
  afterEach(() => {
    clearPendingAuthGateIntent();
  });

  it('maps intents to title/body translation keys', () => {
    const intents: AuthGateIntent[] = [
      'facility-detail',
      'municipal-hidden',
      'community-teaser',
      'contribute',
      'google-maps',
    ];
    for (const intent of intents) {
      expect(authGateTitleKey(intent)).toMatch(/^authGate\./);
      expect(authGateBodyKey(intent)).toMatch(/^authGate\./);
    }
  });

  it('stores and consumes google-maps resume metadata without URL', () => {
    setPendingAuthGateIntent({
      intent: 'google-maps',
      facilityId: 'fac-gm',
      latitude: 38.4237,
      longitude: 27.1428,
    });
    expect(peekPendingAuthGateIntent()).toEqual({
      intent: 'google-maps',
      facilityId: 'fac-gm',
      latitude: 38.4237,
      longitude: 27.1428,
    });
    expect(consumePendingAuthGateIntent()?.intent).toBe('google-maps');
  });

  it('stores and consumes non-sensitive resume metadata', () => {
    setPendingAuthGateIntent({ intent: 'facility-detail', facilityId: 'fac-1' });
    expect(peekPendingAuthGateIntent()).toEqual({
      intent: 'facility-detail',
      facilityId: 'fac-1',
    });
    expect(consumePendingAuthGateIntent()).toEqual({
      intent: 'facility-detail',
      facilityId: 'fac-1',
    });
    expect(peekPendingAuthGateIntent()).toBeNull();
  });
});
