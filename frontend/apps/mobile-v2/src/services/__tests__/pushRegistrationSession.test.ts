import { waitFor } from '@testing-library/react-native';
import { useAuthStore } from '@/state/authStore';
import { registerForPushNotifications, unregisterPushToken } from '../pushNotifications';

// jest.mock calls are hoisted above the imports; the factories read these mocks only when called.
const mockRegisterDeviceToken = jest.fn();
const mockDeactivateDeviceToken = jest.fn();
const mockAddPushTokenListener = jest.fn();

jest.mock('@/services/api', () => ({
  notificationsApi: {
    registerDeviceToken: (...args: unknown[]) => mockRegisterDeviceToken(...args),
    deactivateDeviceToken: (...args: unknown[]) => mockDeactivateDeviceToken(...args),
  },
}));

jest.mock('expo-notifications', () => ({
  getPermissionsAsync: async () => ({ granted: true, canAskAgain: true }),
  requestPermissionsAsync: async () => ({ granted: true }),
  getExpoPushTokenAsync: async () => ({ data: 'ExponentPushToken[shared-device]' }),
  addPushTokenListener: (...args: unknown[]) => mockAddPushTokenListener(...args),
}));

const DEVICE_TOKEN = 'ExponentPushToken[shared-device]';

function signIn(id: string) {
  useAuthStore.getState().setSession({
    id,
    email: `${id}@parkio.test`,
    roles: ['USER'],
    status: 'ACTIVE',
    emailVerified: true,
  } as never);
}

/** Session loss: expiry, a revoked or failed refresh, or sign-out. All of them call clearSession(). */
function loseSession() {
  useAuthStore.getState().clearSession();
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((settle) => {
    resolve = settle;
  });
  return { promise, resolve };
}

/**
 * H-05 (#546): a registration belongs to the session that made it. When another account signs in
 * in the same app process, the token must be registered again so the server hands it over from
 * the previous account.
 */
describe('push registration across sessions', () => {
  beforeEach(() => {
    let registered = 0;
    mockRegisterDeviceToken.mockImplementation(async () => {
      registered += 1;
      return { id: `backend-token-${registered}` };
    });
    mockDeactivateDeviceToken.mockResolvedValue(undefined);
    mockAddPushTokenListener.mockReturnValue({ remove: jest.fn() });
  });

  afterEach(async () => {
    await unregisterPushToken();
    useAuthStore.setState({ status: 'anonymous', user: null });
    jest.clearAllMocks();
  });

  it('registers the token again when another account signs in after the session was lost', async () => {
    signIn('account-a');
    await registerForPushNotifications();

    loseSession();
    signIn('account-b');
    const registration = await registerForPushNotifications();

    expect(mockRegisterDeviceToken).toHaveBeenCalledTimes(2);
    expect(mockRegisterDeviceToken.mock.calls[1][0]).toBe(DEVICE_TOKEN);
    expect(registration?.backendTokenId).toBe('backend-token-2');
    expect(mockDeactivateDeviceToken).not.toHaveBeenCalled();
  });

  it('keeps no registration that completes after its session ended', async () => {
    const backend = deferred<{ id: string }>();
    mockRegisterDeviceToken.mockReturnValueOnce(backend.promise);
    signIn('account-a');
    const pending = registerForPushNotifications();
    await waitFor(() => expect(mockRegisterDeviceToken).toHaveBeenCalledTimes(1));

    loseSession();
    backend.resolve({ id: 'backend-token-a' });

    await expect(pending).resolves.toBeNull();
    signIn('account-b');
    await registerForPushNotifications();
    expect(mockRegisterDeviceToken).toHaveBeenCalledTimes(2);
  });

  it('keeps no token refresh that completes after its session ended', async () => {
    let onTokenRefresh!: (token: { data: string }) => void;
    mockAddPushTokenListener.mockImplementation((listener: (token: { data: string }) => void) => {
      onTokenRefresh = listener;
      return { remove: jest.fn() };
    });
    signIn('account-a');
    await registerForPushNotifications();
    const refreshed = deferred<{ id: string }>();
    mockRegisterDeviceToken.mockReturnValueOnce(refreshed.promise);

    onTokenRefresh({ data: 'ExponentPushToken[rotated]' });
    loseSession();
    refreshed.resolve({ id: 'backend-token-rotated' });
    // Let the refresh's completion run before the next account signs in.
    await new Promise((settle) => setTimeout(settle, 0));

    signIn('account-b');
    await registerForPushNotifications();
    expect(mockRegisterDeviceToken).toHaveBeenCalledTimes(3);
  });

  it('forgets the registration even when the server refuses the deactivation', async () => {
    signIn('account-a');
    await registerForPushNotifications();
    mockDeactivateDeviceToken.mockRejectedValueOnce(new Error('403 ACCOUNT_NOT_ACTIVE'));
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);

    await expect(unregisterPushToken()).resolves.toBeUndefined();

    warn.mockRestore();
    expect(mockDeactivateDeviceToken).toHaveBeenCalledWith('backend-token-1');
    await registerForPushNotifications();
    expect(mockRegisterDeviceToken).toHaveBeenCalledTimes(2);
  });
});
