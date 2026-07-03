import * as Notifications from 'expo-notifications';
import { addNotificationTapListener } from '../pushNotifications';

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

function responseWithData(data: unknown) {
  return { notification: { request: { content: { data } } } } as Notifications.NotificationResponse;
}

describe('addNotificationTapListener', () => {
  beforeEach(() => jest.clearAllMocks());

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
});
