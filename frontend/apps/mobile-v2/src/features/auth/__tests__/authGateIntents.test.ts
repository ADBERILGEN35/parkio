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
    ];
    for (const intent of intents) {
      expect(authGateTitleKey(intent)).toMatch(/^authGate\./);
      expect(authGateBodyKey(intent)).toMatch(/^authGate\./);
    }
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
