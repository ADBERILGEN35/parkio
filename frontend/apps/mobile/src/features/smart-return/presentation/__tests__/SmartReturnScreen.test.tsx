import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, waitFor, type RenderResult } from '@testing-library/react-native';
import type { ReactElement } from 'react';
import type { SmartReturnSettings } from '@parkio/types';
import { ToastProvider } from '@/providers/ToastProvider';
import { usersApi, geocodingApi } from '@/services/api';
import { renderWithProviders } from '@/test/renderWithProviders';
import { SmartReturnScreen } from '../SmartReturnScreen';

jest.mock('@/services/api', () => ({
  usersApi: {
    getSmartReturn: jest.fn(),
    updateSmartReturnSettings: jest.fn(),
    smartReturnLeftByCar: jest.fn(),
    smartReturnNotByCar: jest.fn(),
    cancelSmartReturnToday: jest.fn(),
  },
  geocodingApi: {
    searchPlaces: jest.fn(),
  },
}));

const mockedUsers = usersApi as jest.Mocked<typeof usersApi>;
const mockedGeocoding = geocodingApi as jest.Mocked<typeof geocodingApi>;

function makeSettings(overrides: Partial<SmartReturnSettings> = {}): SmartReturnSettings {
  return {
    enabled: false,
    homeLatitude: null,
    homeLongitude: null,
    homeLabel: null,
    defaultReturnTime: null,
    reminderLeadMinutes: 30,
    lastPromptDate: null,
    todayStatus: 'UNKNOWN',
    todayExpectedReturnAt: null,
    todayReturnCheckCompletedAt: null,
    todayNotificationSentAt: null,
    ...overrides,
  };
}

const configuredSettings = makeSettings({
  enabled: true,
  homeLatitude: 38.44,
  homeLongitude: 27.14,
  homeLabel: 'Alsancak, İzmir',
  defaultReturnTime: '18:30',
});

function renderScreen(): RenderResult {
  // gcTime Infinity: no garbage-collection setTimeout to leak past teardown.
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false, gcTime: Infinity } },
  });
  const withProviders = (ui: ReactElement) => (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>{ui}</ToastProvider>
    </QueryClientProvider>
  );
  return renderWithProviders(withProviders(<SmartReturnScreen />));
}

// Freeze only Date (real timers stay live for react-query, debounce, waitFor)
// so "later today" logic is deterministic: it is always 10:00 in these tests.
beforeEach(() => {
  jest.useFakeTimers({
    doNotFake: [
      'hrtime',
      'nextTick',
      'performance',
      'queueMicrotask',
      'requestAnimationFrame',
      'cancelAnimationFrame',
      'requestIdleCallback',
      'cancelIdleCallback',
      'setImmediate',
      'clearImmediate',
      'setInterval',
      'clearInterval',
      'setTimeout',
      'clearTimeout',
    ],
  });
  jest.setSystemTime(new Date(2026, 6, 3, 10, 0, 0));
});

afterEach(() => {
  jest.useRealTimers();
  jest.clearAllMocks();
});

describe('SmartReturnScreen — load states', () => {
  it('shows a skeleton while settings load', () => {
    mockedUsers.getSmartReturn.mockReturnValue(new Promise(() => {}));
    const { getByTestId } = renderScreen();
    expect(getByTestId('smartReturn.loading')).toBeTruthy();
  });

  it('shows an error state and recovers on retry', async () => {
    mockedUsers.getSmartReturn
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(makeSettings());
    const { findByText, getByText } = renderScreen();

    expect(await findByText('Couldn’t load your Smart Return settings')).toBeTruthy();
    fireEvent.press(getByText('Try again'));
    expect(await findByText('Never circle the block again')).toBeTruthy();
  });
});

describe('SmartReturnScreen — setup flow', () => {
  it('shows the benefits pitch and reveals the form on enable', async () => {
    mockedUsers.getSmartReturn.mockResolvedValue(makeSettings());
    const { findByTestId, getByTestId, queryByTestId, getByText } = renderScreen();

    fireEvent.press(await findByTestId('smartReturn.setup.enable'));
    expect(getByTestId('smartReturn.home.search')).toBeTruthy();
    expect(getByText('Private by design')).toBeTruthy();
    expect(queryByTestId('smartReturn.settings.turnOff')).toBeNull();
  });

  it('searches, selects a home area, and saves the settings', async () => {
    mockedUsers.getSmartReturn.mockResolvedValue(makeSettings());
    mockedGeocoding.searchPlaces.mockResolvedValue([
      {
        id: 'g1',
        displayName: 'Alsancak, Konak, İzmir',
        primary: 'Alsancak',
        secondary: 'Konak, İzmir',
        lat: 38.44,
        lng: 27.14,
      },
    ]);
    mockedUsers.updateSmartReturnSettings.mockResolvedValue(configuredSettings);

    const { findByTestId, getByTestId, findByText } = renderScreen();
    fireEvent.press(await findByTestId('smartReturn.setup.enable'));

    fireEvent.changeText(getByTestId('smartReturn.home.search'), 'Alsancak');
    fireEvent.press(await findByTestId('smartReturn.home.result.g1'));

    // Selection collapses into the saved chip showing the label, never coordinates.
    expect(await findByText('Konak, İzmir')).toBeTruthy();

    fireEvent.press(getByTestId('smartReturn.settings.save'));
    // TanStack Query v5 invokes mutationFn(variables, context) — assert the body only.
    await waitFor(() =>
      expect(mockedUsers.updateSmartReturnSettings.mock.calls[0]?.[0]).toEqual({
        enabled: true,
        homeLatitude: 38.44,
        homeLongitude: 27.14,
        homeLabel: 'Konak, İzmir',
        defaultReturnTime: '18:30',
        reminderLeadMinutes: 30,
      }),
    );

    // Server response flips the screen into the configured (Today-first) view.
    expect(await findByText('Are you driving today?')).toBeTruthy();
  });

  it('blocks enabling without a saved home area', async () => {
    mockedUsers.getSmartReturn.mockResolvedValue(makeSettings());
    const { findByTestId, getByTestId, findByText } = renderScreen();

    fireEvent.press(await findByTestId('smartReturn.setup.enable'));
    fireEvent.press(getByTestId('smartReturn.settings.save'));

    expect(await findByText('Choose a saved home area before enabling Smart Return')).toBeTruthy();
    expect(mockedUsers.updateSmartReturnSettings).not.toHaveBeenCalled();
  });
});

describe('SmartReturnScreen — today flow', () => {
  it('answers "yes, driving" and saves the expected return time', async () => {
    mockedUsers.getSmartReturn.mockResolvedValue(configuredSettings);
    const returnAt = new Date(2026, 6, 3, 19, 30, 0);
    mockedUsers.smartReturnLeftByCar.mockResolvedValue({
      ...configuredSettings,
      todayStatus: 'LEFT_BY_CAR',
      todayExpectedReturnAt: returnAt.toISOString(),
    });

    const { getByTestId, findByText, getByText } = renderScreen();
    expect(await findByText('Are you driving today?')).toBeTruthy();
    expect(getByText('Not set')).toBeTruthy();

    fireEvent.press(getByTestId('smartReturn.today.yes'));
    // Picker starts from the usual return time and previews the check clock.
    expect(getByText('18')).toBeTruthy();
    expect(getByText('30')).toBeTruthy();
    expect(getByText(/We’ll check around/)).toBeTruthy();

    fireEvent.press(getByTestId('smartReturn.today.time.hour.up'));
    fireEvent.press(getByTestId('smartReturn.today.save'));

    await waitFor(() =>
      expect(mockedUsers.smartReturnLeftByCar.mock.calls[0]?.[0]).toEqual({
        expectedReturnAt: returnAt.toISOString(),
      }),
    );
    expect(await findByText('Today’s Smart Return is active')).toBeTruthy();
    expect(getByText('Active')).toBeTruthy();
  });

  it('rejects a return time that is not later today', async () => {
    // 23:30 now; the 18:30 default is in the past.
    jest.setSystemTime(new Date(2026, 6, 3, 23, 30, 0));
    mockedUsers.getSmartReturn.mockResolvedValue(configuredSettings);

    const { findByTestId, getByTestId, findByText } = renderScreen();
    fireEvent.press(await findByTestId('smartReturn.today.yes'));
    fireEvent.press(getByTestId('smartReturn.today.save'));

    expect(await findByText('Pick a return time later today.')).toBeTruthy();
    expect(mockedUsers.smartReturnLeftByCar).not.toHaveBeenCalled();
  });

  it('answers "not by car" and offers a way back', async () => {
    mockedUsers.getSmartReturn.mockResolvedValue(configuredSettings);
    mockedUsers.smartReturnNotByCar.mockResolvedValue({
      ...configuredSettings,
      todayStatus: 'NOT_BY_CAR',
    });

    const { findByTestId, findByText, getByTestId, getByText } = renderScreen();
    fireEvent.press(await findByTestId('smartReturn.today.no'));

    await waitFor(() => expect(mockedUsers.smartReturnNotByCar).toHaveBeenCalled());
    expect(await findByText('No Smart Return scheduled today.')).toBeTruthy();
    expect(getByText('Not today')).toBeTruthy();
    expect(getByTestId('smartReturn.today.changedMind')).toBeTruthy();
  });

  it('cancels an active plan and re-asks the driving question', async () => {
    const returnAt = new Date(2026, 6, 3, 18, 30, 0);
    mockedUsers.getSmartReturn.mockResolvedValue({
      ...configuredSettings,
      todayStatus: 'LEFT_BY_CAR',
      todayExpectedReturnAt: returnAt.toISOString(),
    });
    mockedUsers.cancelSmartReturnToday.mockResolvedValue({
      ...configuredSettings,
      todayStatus: 'CANCELLED',
    });

    const { findByTestId, findByText, getByText } = renderScreen();
    expect(await findByText('Today’s Smart Return is active')).toBeTruthy();

    fireEvent.press(await findByTestId('smartReturn.today.cancel'));
    await waitFor(() => expect(mockedUsers.cancelSmartReturnToday).toHaveBeenCalled());
    expect(await findByText('Today’s reminder was cancelled. Driving again?')).toBeTruthy();
    expect(getByText('Are you driving today?')).toBeTruthy();
  });

  it('keeps settings collapsed behind the secondary toggle with turn-off available', async () => {
    mockedUsers.getSmartReturn.mockResolvedValue(configuredSettings);
    mockedUsers.updateSmartReturnSettings.mockResolvedValue(makeSettings());

    const { findByTestId, getByTestId, queryByTestId, findByText } = renderScreen();
    expect(await findByTestId('smartReturn.settings.toggle')).toBeTruthy();
    expect(queryByTestId('smartReturn.settings.turnOff')).toBeNull();

    fireEvent.press(getByTestId('smartReturn.settings.toggle'));
    fireEvent.press(getByTestId('smartReturn.settings.turnOff'));

    await waitFor(() =>
      expect(mockedUsers.updateSmartReturnSettings.mock.calls[0]?.[0]).toEqual({ enabled: false }),
    );
    // Disabled settings land back on the setup pitch.
    expect(await findByText('Never circle the block again')).toBeTruthy();
  });
});
