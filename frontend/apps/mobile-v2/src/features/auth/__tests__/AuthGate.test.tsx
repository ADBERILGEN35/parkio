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

  it('CLOSED registration-about routes to register without stashing pending intent', () => {
    const onDismiss = jest.fn();
    const { getByText } = renderWithProviders(
      <AuthGate visible intent="contribute" onDismiss={onDismiss} />,
    );
    expect(getByText('Kayıt hakkında bilgi')).toBeTruthy();
    fireEvent.press(getByText('Kayıt hakkında bilgi'));
    expect(peekPendingAuthGateIntent()).toBeNull();
    expect(onDismiss).toHaveBeenCalled();
    expect(mockPush).toHaveBeenCalledWith('/(auth)/register');
  });

  it('google-maps intent stores validated coordinates and never Kayıt ol', () => {
    const onDismiss = jest.fn();
    const { getByText, queryByText } = renderWithProviders(
      <AuthGate
        visible
        intent="google-maps"
        resume={{ facilityId: 'fac-gm', latitude: 38.4, longitude: 27.1 }}
        onDismiss={onDismiss}
      />,
    );
    expect(getByText("Google Maps'te aç")).toBeTruthy();
    expect(getByText(/Google Maps'te açmak ve yol tarifi/)).toBeTruthy();
    expect(queryByText('Kayıt ol')).toBeNull();
    fireEvent.press(getByText('Giriş yap'));
    expect(peekPendingAuthGateIntent()).toEqual({
      intent: 'google-maps',
      facilityId: 'fac-gm',
      latitude: 38.4,
      longitude: 27.1,
    });
    expect(mockPush).toHaveBeenCalledWith('/(auth)/login');
  });
});
