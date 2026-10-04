import { fireEvent } from '@testing-library/react-native';
import { ParkioApiError } from '@parkio/api-client';
import { tr } from '@/i18n/translations';
import { renderWithProviders } from '@/test/renderWithProviders';

const mockReplace = jest.fn();
const mockVerifyEmail = jest.fn();

jest.mock('expo-router', () => ({
  useLocalSearchParams: () => ({ token: 'spent-token' }),
  useRouter: () => ({ replace: mockReplace, push: jest.fn(), back: jest.fn(), canGoBack: () => false }),
}));

jest.mock('@/services/api', () => ({
  authApi: {
    verifyEmail: (...args: unknown[]) => mockVerifyEmail(...args),
    resendVerification: jest.fn(),
  },
}));

jest.mock('@/providers/ToastProvider', () => ({
  useToast: () => ({ show: jest.fn() }),
}));

import VerifyEmailScreen from '../../../../app/(auth)/verify-email';

/**
 * A verification link is spent on first use (CL-F35): a second click gets
 * INVALID_VERIFICATION_TOKEN even when the account is verified, so the screen explains
 * both outcomes and offers sign-in next to requesting a new link (B5).
 */
describe('VerifyEmailScreen with a spent link', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('shows the localized copy instead of the backend text and offers sign-in', async () => {
    mockVerifyEmail.mockRejectedValue(
      new ParkioApiError(400, {
        code: 'INVALID_VERIFICATION_TOKEN',
        message: 'Email verification token is invalid or expired.',
        traceId: 'trc_spent',
        timestamp: new Date().toISOString(),
      }),
    );

    const { findByText, getByText, queryByText } = renderWithProviders(<VerifyEmailScreen />);

    expect(await findByText(tr['auth.verify.linkInvalid'])).toBeTruthy();
    expect(queryByText('Email verification token is invalid or expired.')).toBeNull();
    expect(mockVerifyEmail).toHaveBeenCalledWith({ token: 'spent-token' });

    fireEvent.press(getByText(tr['onboarding.welcome.signIn']));
    expect(mockReplace).toHaveBeenCalledWith('/(auth)/login');
  });
});
