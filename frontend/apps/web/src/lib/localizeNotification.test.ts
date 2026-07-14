import type { AppNotification } from '@parkio/types';
import { beforeEach, describe, expect, it } from 'vitest';
import { initI18n } from '@/i18n';
import { localizeNotification } from './localizeNotification';

describe('localizeNotification', () => {
  beforeEach(async () => {
    await initI18n('en');
  });

  it('localizes LEVEL_UP from metadata.level', () => {
    const n = {
      id: '1',
      type: 'LEVEL_UP',
      channel: 'IN_APP',
      title: 'Level up!',
      body: 'Congratulations - you reached level 3.',
      metadata: { level: '3', deeplink: '/gamification' },
      status: 'UNREAD',
      createdAt: '2026-07-14T00:00:00Z',
      readAt: null,
    } as AppNotification;

    expect(localizeNotification(n)).toEqual({
      title: 'Level up!',
      body: expect.stringContaining('3'),
    });
  });

  it('localizes SMART_RETURN_PROMPT without metadata', async () => {
    await initI18n('tr');
    const n = {
      id: '2',
      type: 'SMART_RETURN_PROMPT',
      channel: 'IN_APP',
      title: 'Are you driving today?',
      body: 'English body',
      metadata: {},
      status: 'UNREAD',
      createdAt: '2026-07-14T00:00:00Z',
      readAt: null,
    } as AppNotification;

    const copy = localizeNotification(n);
    expect(copy.title).not.toBe('Are you driving today?');
    expect(copy.body).not.toBe('English body');
  });

  it('falls back to stored English for legacy WARNING without messageKey', () => {
    const n = {
      id: '3',
      type: 'WARNING',
      channel: 'IN_APP',
      title: 'Heads up',
      body: 'Legacy free text.',
      metadata: {},
      status: 'UNREAD',
      createdAt: '2026-07-14T00:00:00Z',
      readAt: null,
    } as AppNotification;

    expect(localizeNotification(n)).toEqual({
      title: 'Heads up',
      body: 'Legacy free text.',
    });
  });

  it('localizes accountSuspended WARNING by messageKey', async () => {
    await initI18n('tr');
    const n = {
      id: '4',
      type: 'WARNING',
      channel: 'IN_APP',
      title: 'Heads up',
      body: 'Your account has been suspended by moderation.',
      metadata: { messageKey: 'accountSuspended' },
      status: 'UNREAD',
      createdAt: '2026-07-14T00:00:00Z',
      readAt: null,
    } as AppNotification;

    const copy = localizeNotification(n);
    expect(copy.body).not.toBe(n.body);
    expect(copy.title.length).toBeGreaterThan(0);
  });
});
