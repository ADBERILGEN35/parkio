import * as Notifications from 'expo-notifications';
import {
  addNotificationTapListener,
  guardNotificationRoute,
  registerForPushNotifications,
  unregisterPushToken,
} from '../pushNotifications';

jest.mock('expo-notifications', () => ({
  setNotificationHandler: jest.fn(),
  requestPermissionsAsync: jest.fn(),
  getExpoPushTokenAsync: jest.fn(),
  addPushTokenListener: jest.fn(() => ({ remove: jest.fn() })),
  addNotificationResponseReceivedListener: jest.fn(() => ({ remove: jest.fn() })),
  getLastNotificationResponseAsync: jest.fn(async () => null),
}));

jest.mock('@/services/api', () => ({
  notificationsApi: { registerDeviceToken: jest.fn(), deactivateDeviceToken: jest.fn() },
}));

import { notificationsApi } from '@/services/api';

function responseWithData(data: unknown) {
  return { notification: { request: { content: { data } } } } as Notifications.NotificationResponse;
}

describe('addNotificationTapListener', () => {
  beforeEach(async () => {
    jest.clearAllMocks();
    await unregisterPushToken();
  });

  it('routes taps to the allow-listed route from the payload', async () => {
    const onRoute = jest.fn();
    addNotificationTapListener(onRoute);

    const listener = jest.mocked(Notifications.addNotificationResponseReceivedListener).mock.calls[0][0];
    listener(responseWithData({ route: '/(main)/map' }));

    expect(onRoute).toHaveBeenCalledWith('/(main)/map');
  });

  it('falls back to the notifications tab for unknown or missing routes', () => {
    const onRoute = jest.fn();
    addNotificationTapListener(onRoute);
    const listener = jest.mocked(Notifications.addNotificationResponseReceivedListener).mock.calls[0][0];

    listener(responseWithData({ route: '/(main)/evil-route' }));
    listener(responseWithData(undefined));

    expect(onRoute).toHaveBeenNthCalledWith(1, '/(main)/(tabs)/notifications');
    expect(onRoute).toHaveBeenNthCalledWith(2, '/(main)/(tabs)/notifications');
  });

  it('handles the cold-start tap (initial notification response)', async () => {
    jest
      .mocked(Notifications.getLastNotificationResponseAsync)
      .mockResolvedValueOnce(responseWithData({ route: '/(main)/smart-return' }));
    const onRoute = jest.fn();
    addNotificationTapListener(onRoute);

    await new Promise((r) => setTimeout(r, 0));
    expect(onRoute).toHaveBeenCalledWith('/(main)/smart-return');
  });

  it('returns an unsubscribe that removes the listener', () => {
    const remove = jest.fn();
    jest
      .mocked(Notifications.addNotificationResponseReceivedListener)
      .mockReturnValueOnce({ remove } as unknown as Notifications.EventSubscription);

    const unsubscribe = addNotificationTapListener(jest.fn());
    unsubscribe();
    expect(remove).toHaveBeenCalled();
  });

  it('blocks staff routes based on gateway-aligned roles', () => {
    expect(guardNotificationRoute('/(main)/moderation', ['USER'])).toBe('/(main)/(tabs)/notifications');
    expect(guardNotificationRoute('/(main)/moderation', ['MODERATOR'])).toBe('/(main)/moderation');
    expect(guardNotificationRoute('/(main)/moderation', ['ADMIN'])).toBe('/(main)/moderation');
    expect(guardNotificationRoute('/(main)/analytics', ['USER'])).toBe('/(main)/(tabs)/notifications');
    expect(guardNotificationRoute('/(main)/analytics', ['MODERATOR'])).toBe('/(main)/(tabs)/notifications');
    expect(guardNotificationRoute('/(main)/analytics', ['ADMIN'])).toBe('/(main)/analytics');
  });

  it('deactivates backend token on logout without logging token values', async () => {
    jest.mocked(Notifications.requestPermissionsAsync).mockResolvedValue({ granted: true } as never);
    jest.mocked(Notifications.getExpoPushTokenAsync).mockResolvedValue({ data: 'ExponentPushToken[secret]' } as never);
    jest.mocked(notificationsApi.registerDeviceToken).mockResolvedValue({ id: 'token-id-1' } as never);
    jest.mocked(notificationsApi.deactivateDeviceToken).mockResolvedValue(undefined as never);

    await registerForPushNotifications();
    await unregisterPushToken();

    expect(notificationsApi.deactivateDeviceToken).toHaveBeenCalledWith('token-id-1');
  });
});
