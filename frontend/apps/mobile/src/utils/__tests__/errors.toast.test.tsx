import { UnauthorizedError } from '@parkio/api-client';
import { fireEvent, screen } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import { Pressable } from 'react-native';
import { LocaleProvider, useLocale } from '@/i18n/LocaleProvider';
import { ToastProvider, useToast } from '@/providers/ToastProvider';
import { renderWithProviders } from '@/test/renderWithProviders';
import { toUserMessage } from '../errors';

/** A wrong current password on change-password, as the shared client throws it (#604). */
const wrongPassword = new UnauthorizedError({
  code: 'INVALID_CREDENTIALS',
  message: 'Invalid email or password.',
  traceId: 't',
  timestamp: '2026-01-01T00:00:00Z',
});

/** Shows the error the way the screens do, `toast.showError(toUserMessage(error))`. */
function ErrorToastHarness() {
  const toast = useToast();
  const { setLocale } = useLocale();
  return (
    <>
      <Pressable testID="show-error" onPress={() => toast.showError(toUserMessage(wrongPassword))} />
      <Pressable testID="use-english" onPress={() => setLocale('en')} />
    </>
  );
}

/** The app's provider order: LocaleProvider wraps ToastProvider (AppProviders). */
function renderHarness() {
  return renderWithProviders(
    <LocaleProvider>
      <ToastProvider>
        <ErrorToastHarness />
      </ToastProvider>
    </LocaleProvider>,
  );
}

beforeEach(async () => {
  await SecureStore.deleteItemAsync('parkio.locale');
});

describe('auth error toast locale', () => {
  it('shows a wrong password in Turkish by default and in English after a switch', async () => {
    renderHarness();

    fireEvent.press(screen.getByTestId('show-error'));
    expect(await screen.findByText('E-posta veya şifre hatalı.')).toBeTruthy();

    fireEvent.press(screen.getByTestId('use-english'));
    expect(await screen.findByText('Incorrect email or password.')).toBeTruthy();
    expect(screen.queryByText('E-posta veya şifre hatalı.')).toBeNull();
  });
});
