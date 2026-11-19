import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { tr } from '@/i18n/translations';
import { renderWithProviders } from '@/test/renderWithProviders';
import SuspendedScreen from '../../../../app/(main)/suspended';

// jest.mock calls are hoisted above the imports; the factories read these mocks only when called.
const mockReplace = jest.fn();
const mockSignOut = jest.fn();
const mockUnregisterPushToken = jest.fn();

jest.mock('expo-router', () => ({
  useRouter: () => ({ replace: mockReplace, push: jest.fn(), back: jest.fn(), canGoBack: () => false }),
}));

jest.mock('@/services/auth', () => ({
  signOut: (...args: unknown[]) => mockSignOut(...args),
}));

jest.mock('@/services/pushNotifications', () => ({
  unregisterPushToken: (...args: unknown[]) => mockUnregisterPushToken(...args),
}));

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((settle) => {
    resolve = settle;
  });
  return { promise, resolve };
}

/**
 * H-05 (#546): the suspended wall releases this device's push token before it ends the session,
 * in the same order as the profile sign-out. The release needs the session that sign-out clears.
 */
describe('suspended screen sign-out', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockSignOut.mockResolvedValue(undefined);
  });

  it('releases the push token before it signs out', async () => {
    const unregister = deferred();
    mockUnregisterPushToken.mockReturnValue(unregister.promise);
    renderWithProviders(<SuspendedScreen />);

    fireEvent.press(screen.getByRole('button', { name: tr['auth.suspended.signOut'] }));

    expect(mockUnregisterPushToken).toHaveBeenCalledTimes(1);
    expect(mockSignOut).not.toHaveBeenCalled();
    expect(mockReplace).not.toHaveBeenCalled();

    unregister.resolve();

    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/(onboarding)/welcome'));
    expect(mockSignOut).toHaveBeenCalledTimes(1);
  });

  it('still signs out when releasing the push token fails', async () => {
    mockUnregisterPushToken.mockRejectedValue(new Error('release failed'));
    renderWithProviders(<SuspendedScreen />);

    fireEvent.press(screen.getByRole('button', { name: tr['auth.suspended.signOut'] }));

    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/(onboarding)/welcome'));
    expect(mockUnregisterPushToken).toHaveBeenCalledTimes(1);
    expect(mockSignOut).toHaveBeenCalledTimes(1);
  });
});
