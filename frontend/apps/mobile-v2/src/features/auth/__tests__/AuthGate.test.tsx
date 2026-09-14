import { fireEvent } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { AuthGate } from '../AuthGate';
import {
  clearPendingAuthGateIntent,
  peekPendingAuthGateIntent,
} from '../authGateIntents';

const mockPush = jest.fn();

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: mockPush, replace: jest.fn() }),
}));

describe('AuthGate', () => {
  afterEach(() => {
    mockPush.mockReset();
    clearPendingAuthGateIntent();
  });

  it('shows login primary and dismiss without Kayıt ol', () => {
    const onDismiss = jest.fn();
    const { getByText, queryByText } = renderWithProviders(
      <AuthGate visible intent="municipal-hidden" onDismiss={onDismiss} />,
    );

    expect(getByText('Tüm park seçeneklerini görün')).toBeTruthy();
    expect(getByText(/Bölgedeki diğer park seçeneklerini/)).toBeTruthy();
    expect(getByText('Giriş yap')).toBeTruthy();
    expect(getByText('Şimdi değil')).toBeTruthy();
    expect(queryByText('Kayıt ol')).toBeNull();
  });

  it('dismiss closes without navigating', () => {
    const onDismiss = jest.fn();
    const { getByText } = renderWithProviders(
      <AuthGate visible intent="community-teaser" onDismiss={onDismiss} />,
    );
    fireEvent.press(getByText('Şimdi değil'));
    expect(onDismiss).toHaveBeenCalled();
    expect(mockPush).not.toHaveBeenCalled();
  });

  it('login stores resume intent and navigates to login', () => {
    const onDismiss = jest.fn();
    const { getByText } = renderWithProviders(
      <AuthGate
        visible
        intent="facility-detail"
        resume={{ facilityId: 'fac-public-1' }}
        onDismiss={onDismiss}
      />,
    );
    fireEvent.press(getByText('Giriş yap'));
    expect(peekPendingAuthGateIntent()).toEqual({
      intent: 'facility-detail',
      facilityId: 'fac-public-1',
    });
    expect(onDismiss).toHaveBeenCalled();
    expect(mockPush).toHaveBeenCalledWith('/(auth)/login');
  });
});
