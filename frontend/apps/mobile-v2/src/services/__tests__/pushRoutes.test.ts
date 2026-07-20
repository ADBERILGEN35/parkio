import { guardNotificationRoute } from '../pushNotifications';

describe('guardNotificationRoute', () => {
  it('lets regular routes through for any role', () => {
    expect(guardNotificationRoute('/(main)/(tabs)/map', [])).toBe('/(main)/(tabs)/map');
    expect(guardNotificationRoute('/(main)/impact', ['USER'])).toBe('/(main)/impact');
  });

  it('blocks moderation for non-staff and allows it for staff', () => {
    expect(guardNotificationRoute('/(main)/moderation', ['USER'])).toBe('/(main)/notifications');
    expect(guardNotificationRoute('/(main)/moderation', ['MODERATOR'])).toBe('/(main)/moderation');
    expect(guardNotificationRoute('/(main)/moderation', ['ADMIN'])).toBe('/(main)/moderation');
  });

  it('blocks analytics for non-admin (including moderators)', () => {
    expect(guardNotificationRoute('/(main)/moderation/analytics', ['MODERATOR'])).toBe(
      '/(main)/notifications',
    );
    expect(guardNotificationRoute('/(main)/moderation/analytics', ['ADMIN'])).toBe(
      '/(main)/moderation/analytics',
    );
  });
});
