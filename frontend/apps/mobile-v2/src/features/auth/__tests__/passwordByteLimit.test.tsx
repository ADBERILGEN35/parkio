import type { ReactNode } from 'react';
import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { utf8ByteLength } from '@parkio/validation';
import { en, tr } from '@/i18n/translations';
import { renderWithProviders } from '@/test/renderWithProviders';

const mockReplace = jest.fn();
const mockRegister = jest.fn();
const mockResetPassword = jest.fn();
const mockChangePassword = jest.fn();

jest.mock('expo-router', () => ({
  Link: ({ children }: { children: ReactNode }) => children,
  useLocalSearchParams: () => ({ token: 'synthetic-reset-token' }),
  useRouter: () => ({ replace: mockReplace, push: jest.fn(), back: jest.fn(), canGoBack: () => false }),
}));

jest.mock('@/services/api', () => ({
  authApi: {
    getRegistrationMode: jest.fn(async () => ({ mode: 'OPEN' })),
    register: (...args: unknown[]) => mockRegister(...args),
    resetPassword: (...args: unknown[]) => mockResetPassword(...args),
    changePassword: (...args: unknown[]) => mockChangePassword(...args),
  },
}));

jest.mock('@/services/productAnalytics', () => ({ trackProductEvent: jest.fn() }));
jest.mock('@/features/auth/pendingProfile', () => ({ stashPendingProfile: jest.fn(async () => undefined) }));
jest.mock('@/providers/ToastProvider', () => ({ useToast: () => ({ show: jest.fn() }) }));

import RegisterScreen from '../../../../app/(auth)/register';
import ResetPasswordScreen from '../../../../app/(auth)/reset-password';
import ChangePasswordScreen from '../../../../app/(main)/profile/change-password';

// "ş" is two bytes in UTF-8, so these strong passwords are far shorter in characters than in bytes.
const TOO_LONG = `Aa1${'ş'.repeat(35)}`;
const EXACTLY_72 = `Aa1${'ş'.repeat(34)}b`;

/**
 * CL-F36 follow-up: the auth service rejects passwords over 72 UTF-8 bytes with PASSWORD_TOO_LONG.
 * Every mobile-v2 password screen says so before submit, and a multibyte password of exactly 72 bytes
 * still goes through.
 */
describe('72-byte password limit hint', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockRegister.mockResolvedValue(undefined);
    mockResetPassword.mockResolvedValue(undefined);
    mockChangePassword.mockResolvedValue(undefined);
  });

  it('uses passwords on both sides of the limit', () => {
    expect(utf8ByteLength(TOO_LONG)).toBe(73);
    expect(TOO_LONG).toHaveLength(38);
    expect(utf8ByteLength(EXACTLY_72)).toBe(72);
  });

  it('has the message in Turkish and English', () => {
    expect(tr['auth.passwordTooLong']).toContain('72 bayt');
    expect(en['auth.passwordTooLong']).toContain('72 bytes');
  });

  describe('register', () => {
    async function fillForm(password: string) {
      fireEvent.changeText(await screen.findByLabelText(tr['profile.account.displayName']), 'Ayşe');
      fireEvent.changeText(screen.getByLabelText(tr['auth.email']), 'ayse@example.test');
      fireEvent.changeText(screen.getByLabelText(tr['auth.password']), password);
      fireEvent.press(screen.getByRole('checkbox'));
    }

    it('shows the limit and does not submit a password over 72 bytes', async () => {
      renderWithProviders(<RegisterScreen />);
      await fillForm(TOO_LONG);

      expect(screen.getByText(tr['auth.passwordTooLong'])).toBeOnTheScreen();
      fireEvent.press(screen.getByRole('button', { name: tr['auth.register.cta'] }));
      expect(screen.getByText(tr['auth.passwordTooLong'])).toBeOnTheScreen();
      expect(mockRegister).not.toHaveBeenCalled();
    });

    it('accepts a multibyte password of exactly 72 bytes', async () => {
      renderWithProviders(<RegisterScreen />);
      await fillForm(EXACTLY_72);

      expect(screen.queryByText(tr['auth.passwordTooLong'])).toBeNull();
      fireEvent.press(screen.getByRole('button', { name: tr['auth.register.cta'] }));
      await waitFor(() =>
        expect(mockReplace).toHaveBeenCalledWith(expect.objectContaining({ pathname: '/(auth)/check-email' })),
      );
      expect(mockRegister).toHaveBeenCalledWith(
        expect.objectContaining({ email: 'ayse@example.test', password: EXACTLY_72 }),
      );
    });
  });

  describe('reset password', () => {
    it('shows the limit and does not submit a password over 72 bytes', () => {
      renderWithProviders(<ResetPasswordScreen />);
      fireEvent.changeText(screen.getByLabelText(tr['auth.reset.newPassword']), TOO_LONG);
      fireEvent.changeText(screen.getByLabelText(tr['auth.reset.confirmPassword']), TOO_LONG);

      expect(screen.getByText(tr['auth.passwordTooLong'])).toBeOnTheScreen();
      fireEvent.press(screen.getByRole('button', { name: tr['auth.reset.cta'] }));
      expect(mockResetPassword).not.toHaveBeenCalled();
    });

    it('accepts a multibyte password of exactly 72 bytes', async () => {
      renderWithProviders(<ResetPasswordScreen />);
      fireEvent.changeText(screen.getByLabelText(tr['auth.reset.newPassword']), EXACTLY_72);
      fireEvent.changeText(screen.getByLabelText(tr['auth.reset.confirmPassword']), EXACTLY_72);

      expect(screen.queryByText(tr['auth.passwordTooLong'])).toBeNull();
      fireEvent.press(screen.getByRole('button', { name: tr['auth.reset.cta'] }));
      expect(await screen.findByText(tr['auth.reset.successTitle'])).toBeOnTheScreen();
      expect(mockResetPassword).toHaveBeenCalledWith({ token: 'synthetic-reset-token', newPassword: EXACTLY_72 });
    });
  });

  describe('change password', () => {
    it('shows the limit and keeps save disabled for a password over 72 bytes', () => {
      renderWithProviders(<ChangePasswordScreen />);
      fireEvent.changeText(screen.getByLabelText(tr['profile.password.current']), 'Current-password-1');
      fireEvent.changeText(screen.getByLabelText(tr['profile.password.new']), TOO_LONG);

      expect(screen.getByText(tr['auth.passwordTooLong'])).toBeOnTheScreen();
      expect(screen.getByRole('button', { name: tr['common.save'] })).toBeDisabled();
    });

    it('enables save for a multibyte password of exactly 72 bytes', () => {
      renderWithProviders(<ChangePasswordScreen />);
      fireEvent.changeText(screen.getByLabelText(tr['profile.password.current']), 'Current-password-1');
      fireEvent.changeText(screen.getByLabelText(tr['profile.password.new']), EXACTLY_72);

      expect(screen.queryByText(tr['auth.passwordTooLong'])).toBeNull();
      expect(screen.getByRole('button', { name: tr['common.save'] })).toBeEnabled();
    });
  });
});
