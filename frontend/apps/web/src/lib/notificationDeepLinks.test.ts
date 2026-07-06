import { describe, expect, it } from 'vitest';
import {
  isAdminOnlyNotificationPath,
  isAllowedNotificationPath,
  isStaffOnlyNotificationPath,
  resolveNotificationNavigation,
} from './notificationDeepLinks';

describe('notificationDeepLinks', () => {
  it('allows known safe paths', () => {
    expect(isAllowedNotificationPath('/map')).toBe(true);
    expect(isAllowedNotificationPath('/spots/550e8400-e29b-41d4-a716-446655440000')).toBe(true);
    expect(isAllowedNotificationPath('/evil')).toBe(false);
  });

  it('marks staff routes', () => {
    expect(isStaffOnlyNotificationPath('/moderation')).toBe(true);
    expect(isStaffOnlyNotificationPath('/analytics')).toBe(true);
    expect(isStaffOnlyNotificationPath('/map')).toBe(false);
  });

  it('marks analytics as admin-only', () => {
    expect(isAdminOnlyNotificationPath('/analytics')).toBe(true);
    expect(isAdminOnlyNotificationPath('/moderation')).toBe(false);
  });

  it('fails closed for unknown deeplinks', () => {
    expect(resolveNotificationNavigation('/evil', 'SYSTEM', ['USER'])).toBe('/notifications');
  });

  it('blocks moderation for non-privileged users', () => {
    expect(resolveNotificationNavigation('/moderation', 'WARNING', ['USER'])).toBe('/notifications');
    expect(resolveNotificationNavigation('/moderation', 'WARNING', ['MODERATOR'])).toBe('/moderation');
  });

  it('blocks analytics for users and moderators', () => {
    expect(resolveNotificationNavigation('/analytics', 'SYSTEM', ['USER'])).toBe('/notifications');
    expect(resolveNotificationNavigation('/analytics', 'SYSTEM', ['MODERATOR'])).toBe('/notifications');
    expect(resolveNotificationNavigation('/analytics', 'SYSTEM', ['ADMIN'])).toBe('/analytics');
  });

  it('maps notification types when deeplink is absent', () => {
    expect(resolveNotificationNavigation(undefined, 'LEVEL_UP', ['USER'])).toBe('/gamification');
    expect(resolveNotificationNavigation(undefined, 'SMART_RETURN_AVAILABLE', ['USER'])).toBe(
      '/map?smartReturn=1',
    );
  });
});
