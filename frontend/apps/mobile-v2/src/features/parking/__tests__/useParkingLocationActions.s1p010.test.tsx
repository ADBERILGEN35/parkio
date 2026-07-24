import { act, renderHook, waitFor } from '@testing-library/react-native';
import { Share } from 'react-native';
import * as Linking from 'expo-linking';
import { LocaleProvider } from '@/i18n/LocaleProvider';
import { ToastProvider } from '@/providers/ToastProvider';
import { ThemeProvider } from '@/theme/ThemeProvider';
import { useAuthStore } from '@/state/authStore';
import * as productAnalytics from '@/services/productAnalytics';
import { useParkingLocationActions } from '../useParkingLocationActions';
import type { ReactNode } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

jest.mock('expo-linking', () => ({
  openURL: jest.fn(),
}));

jest.mock('@/services/productAnalytics', () => {
  const actual = jest.requireActual('@/services/productAnalytics');
  return {
    ...actual,
    trackProductEvent: jest.fn(),
  };
});

function wrapper({ children }: { children: ReactNode }) {
  return (
    <SafeAreaProvider
      initialMetrics={{
        frame: { x: 0, y: 0, width: 393, height: 852 },
        insets: { top: 47, left: 0, right: 0, bottom: 34 },
      }}
    >
      <ThemeProvider>
        <LocaleProvider>
          <ToastProvider>{children}</ToastProvider>
        </LocaleProvider>
      </ThemeProvider>
    </SafeAreaProvider>
  );
}

describe('useParkingLocationActions S1-P0-10', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useAuthStore.setState({
      status: 'authenticated',
      user: { id: 'user-1', email: 'a@b.c', displayName: 'A', roles: ['USER'] } as never,
      sessionEpoch: 1,
    });
    (Linking.openURL as jest.Mock).mockResolvedValue(undefined);
  });

  it('opens maps once and tracks return_to_car_clicked without coordinates', async () => {
    const { result } = renderHook(
      () =>
        useParkingLocationActions({
          sessionId: 'sess-1',
          latitude: 41.0082,
          longitude: 28.9784,
          terminalBusy: false,
        }),
      { wrapper },
    );

    await act(async () => {
      await result.current.navigate();
    });

    expect(Linking.openURL).toHaveBeenCalledTimes(1);
    const url = (Linking.openURL as jest.Mock).mock.calls[0][0] as string;
    expect(url).toContain('41.0082');
    expect(productAnalytics.trackProductEvent).toHaveBeenCalledWith('return_to_car_clicked', {
      platform: expect.any(String),
    });
    const tracked = (productAnalytics.trackProductEvent as jest.Mock).mock.calls[0][1];
    expect(JSON.stringify(tracked)).not.toMatch(/41\.0082|28\.9784|maps:\/\/|openstreetmap/);
  });

  it('ignores duplicate navigate while busy', async () => {
    let resolveOpen: (() => void) | undefined;
    (Linking.openURL as jest.Mock).mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          resolveOpen = resolve;
        }),
    );

    const { result } = renderHook(
      () =>
        useParkingLocationActions({
          sessionId: 'sess-1',
          latitude: 41.0082,
          longitude: 28.9784,
          terminalBusy: false,
        }),
      { wrapper },
    );

    let first: Promise<void>;
    act(() => {
      first = result.current.navigate();
    });
    await waitFor(() => expect(result.current.phase).toBe('navigating'));

    await act(async () => {
      await result.current.navigate();
    });
    expect(Linking.openURL).toHaveBeenCalledTimes(1);

    await act(async () => {
      resolveOpen?.();
      await first!;
    });
  });

  it('tracks parking_location_shared only on sharedAction', async () => {
    const shareSpy = jest.spyOn(Share, 'share').mockResolvedValue({
      action: Share.sharedAction,
    } as never);

    const { result } = renderHook(
      () =>
        useParkingLocationActions({
          sessionId: 'sess-1',
          latitude: 41.0082,
          longitude: 28.9784,
          terminalBusy: false,
        }),
      { wrapper },
    );

    await act(async () => {
      await result.current.share();
    });

    expect(shareSpy).toHaveBeenCalledTimes(1);
    const payload = shareSpy.mock.calls[0][0] as { message?: string };
    expect(payload.message).toContain('openstreetmap.org');
    expect(payload.message).not.toMatch(/sess-1|user-1|ACTIVE|startedAt/i);
    expect(productAnalytics.trackProductEvent).toHaveBeenCalledWith('parking_location_shared', {
      platform: expect.any(String),
    });
  });

  it('does not track success on share dismiss', async () => {
    jest.spyOn(Share, 'share').mockResolvedValue({
      action: Share.dismissedAction,
    } as never);

    const { result } = renderHook(
      () =>
        useParkingLocationActions({
          sessionId: 'sess-1',
          latitude: 41.0082,
          longitude: 28.9784,
          terminalBusy: false,
        }),
      { wrapper },
    );

    await act(async () => {
      await result.current.share();
    });

    expect(productAnalytics.trackProductEvent).not.toHaveBeenCalledWith(
      'parking_location_shared',
      expect.anything(),
    );
  });

  it('falls back to https maps URL when primary open fails', async () => {
    (Linking.openURL as jest.Mock)
      .mockRejectedValueOnce(new Error('no app'))
      .mockResolvedValueOnce(undefined);

    const { result } = renderHook(
      () =>
        useParkingLocationActions({
          sessionId: 'sess-1',
          latitude: 41.0082,
          longitude: 28.9784,
          terminalBusy: false,
        }),
      { wrapper },
    );

    await act(async () => {
      await result.current.navigate();
    });

    expect(Linking.openURL).toHaveBeenCalledTimes(2);
    expect((Linking.openURL as jest.Mock).mock.calls[1][0]).toContain('https://www.openstreetmap.org');
    expect(productAnalytics.trackProductEvent).toHaveBeenCalledWith(
      'return_to_car_clicked',
      expect.any(Object),
    );
  });

  it('disables actions when terminalBusy or invalid coords', () => {
    const { result, rerender } = renderHook(
      (props: { lat: number; busy: boolean }) =>
        useParkingLocationActions({
          sessionId: 'sess-1',
          latitude: props.lat,
          longitude: 28.9784,
          terminalBusy: props.busy,
        }),
      { wrapper, initialProps: { lat: 41.0082, busy: true } },
    );

    expect(result.current.navigateDisabled).toBe(true);
    expect(result.current.shareDisabled).toBe(true);

    rerender({ lat: Number.NaN, busy: false });
    expect(result.current.destinationValid).toBe(false);
    expect(result.current.navigateDisabled).toBe(true);
  });

  it('ignores stale completion after user switch', async () => {
    let resolveOpen: (() => void) | undefined;
    (Linking.openURL as jest.Mock).mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          resolveOpen = resolve;
        }),
    );

    const { result } = renderHook(
      () =>
        useParkingLocationActions({
          sessionId: 'sess-1',
          latitude: 41.0082,
          longitude: 28.9784,
          terminalBusy: false,
        }),
      { wrapper },
    );

    let pending: Promise<void>;
    act(() => {
      pending = result.current.navigate();
    });
    await waitFor(() => expect(result.current.phase).toBe('navigating'));

    act(() => {
      useAuthStore.setState({
        status: 'authenticated',
        user: { id: 'user-2', email: 'b@c.d', displayName: 'B', roles: ['USER'] } as never,
        sessionEpoch: 2,
      });
    });

    await act(async () => {
      resolveOpen?.();
      await pending!;
    });

    expect(productAnalytics.trackProductEvent).not.toHaveBeenCalledWith(
      'return_to_car_clicked',
      expect.anything(),
    );
  });
});