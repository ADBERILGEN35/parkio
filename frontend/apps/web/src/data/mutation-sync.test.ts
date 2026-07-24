import type { Profile } from '@parkio/types';
import { describe, expect, it } from 'vitest';
import { meKeys } from '@/data/keys';
import { createTestQueryClient } from '@/test/utils';

describe('profile mutation cache sync', () => {
  it('replaces the canonical profile entry without touching unrelated keys', () => {
    const client = createTestQueryClient();
    const previous: Profile = {
      id: 'profile-1',
      authUserId: 'user-1',
      email: 'a@parkio.dev',
      displayName: 'A',
      phoneNumber: null,
      city: null,
      status: 'ACTIVE',
      createdAt: '2026-01-01T00:00:00.000Z',
    };
    const next: Profile = { ...previous, displayName: 'Updated' };
    client.setQueryData(meKeys.profile(), previous);
    client.setQueryData(meKeys.stats(), { trustScore: 1 });

    client.setQueryData(meKeys.profile(), next);

    expect(client.getQueryData(meKeys.profile())).toEqual(next);
    expect(client.getQueryData(meKeys.stats())).toEqual({ trustScore: 1 });
  });
});
