import { describe, expect, it } from 'vitest';
import { publicProfileResponseSchema } from './user';

// GET /users/{userId}/public-profile is readable by any signed-in user, so it carries
// no account (moderation) status. The owner reads that from GET /users/me.
const publicProfile = {
  userId: '0b8f6c3a-0000-0000-0000-000000000551',
  displayName: 'Parkio Driver',
  city: 'Istanbul',
  trustBand: 'MEDIUM_TRUST',
  currentLevel: 3,
  memberSince: '2026-01-01T09:00:00Z',
};

describe('public profile contract', () => {
  it('accepts the response without an account status', () => {
    expect(publicProfileResponseSchema.parse(publicProfile)).toEqual(publicProfile);
  });

  it('drops the status that an older user-service still sends', () => {
    const parsed = publicProfileResponseSchema.parse({ ...publicProfile, status: 'SUSPENDED' });

    expect(parsed).toEqual(publicProfile);
    expect(parsed).not.toHaveProperty('status');
  });
});
