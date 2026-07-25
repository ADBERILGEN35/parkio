import { act, renderHook, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';
import {
  ConflictError,
  NetworkError,
  ValidationError,
  createIdempotencyKey,
} from '@parkio/api-client';
import type { LocationState } from '@/features/map/hooks';
import { parkingKeys } from '@/data/keys';
import { useAuthStore } from '@/state/authStore';
import {
  ACTIVE_PARKING_SESSION_EXISTS,
  useStartParkingSession,
} from '../useStartParkingSession';
import * as api from '@/services/api';

jest.mock('@/services/api', () => ({
  parkingApi: {
    startParkingSession: jest.fn(),
    getActiveParkingSession: jest.fn(),
  },
}));

jest.mock('@/hooks/useOnlineStatus', () => ({
  useOnlineStatus: jest.fn(() => true),
}));

const { useOnlineStatus } = jest.requireMock('@/hooks/useOnlineStatus') as {
  useOnlineStatus: jest.Mock;
};

const sessionFixture = {
  id: 'd431ad5a-f8ce-4be2-b4dc-248b47990b39',
  status: 'ACTIVE' as const,
  parkingSource: 'MANUAL' as const,
  startedAt: '2026-07-21T09:00:00Z',
  endedAt: null,
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: null,
  lastConfirmedAt: '2026-07-25T10:00:00.000Z',
  completionType: null,
};

function createLocation(overrides: Partial<LocationState> = {}): LocationState {
  const baseCanAskAgain = overrides.canAskAgain ?? true;
  const base: LocationState = {
    status: 'granted',
    canAskAgain: baseCanAskAgain,
    getCanAskAgain: () => baseCanAskAgain,
    position: { lat: 41.0082, lng: 28.9784 },
    accuracy: 12,
    request: jest.fn(async () => ({ lat: 41.0082, lng: 28.9784 })),
    refresh: jest.fn(async () => ({ lat: 41.0082, lng: 28.9784 })),
  };
  return { ...base, ...overrides };
}

function wrapperFor(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children);
  };
}

describe('useStartParkingSession S1-P0-03', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useOnlineStatus.mockReturnValue(true);
    useAuthStore.setState({
      status: 'authenticated',
      user: { id: 'user-1', email: 'a@b.c', displayName: 'A', roles: ['USER'] } as never,
      sessionEpoch: 1,
    });
  });

  it('sends StartParkingSessionRequest with caller-generated key and updates active cache', async () => {
    (api.parkingApi.startParkingSession as jest.Mock).mockResolvedValueOnce(sessionFixture);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const location = createLocation();

    const { result } = renderHook(() => useStartParkingSession(location), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.start();
    });

    expect(api.parkingApi.startParkingSession).toHaveBeenCalledTimes(1);
    const [body, key] = (api.parkingApi.startParkingSession as jest.Mock).mock.calls[0];
    expect(body).toEqual({ latitude: 41.0082, longitude: 28.9784 });
    expect(typeof key).toBe('string');
    expect(key.length).toBeGreaterThan(8);
    expect(key).not.toBe(createIdempotencyKey()); // different fresh UUID; just ensure not empty
    await waitFor(() => {
      expect(client.getQueryData(parkingKeys.activeSession())).toEqual(sessionFixture);
    });
    expect(result.current.phase).toBe('idle');
    expect(result.current.attemptKey).toBeNull();
  });

  it('double start while busy produces one mutation', async () => {
    let resolveStart!: (value: typeof sessionFixture) => void;
    (api.parkingApi.startParkingSession as jest.Mock).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveStart = resolve;
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useStartParkingSession(createLocation()), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      const p1 = result.current.start();
      const p2 = result.current.start();
      resolveStart(sessionFixture);
      await Promise.all([p1, p2]);
    });

    expect(api.parkingApi.startParkingSession).toHaveBeenCalledTimes(1);
  });

  it('reuses the same idempotency key after ambiguous network failure', async () => {
    (api.parkingApi.startParkingSession as jest.Mock)
      .mockRejectedValueOnce(new NetworkError('offline-boom'))
      .mockResolvedValueOnce(sessionFixture);
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(null);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useStartParkingSession(createLocation()), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.start();
    });
    expect(result.current.phase).toBe('ambiguous');
    const firstKey = (api.parkingApi.startParkingSession as jest.Mock).mock.calls[0][1];
    expect(result.current.attemptKey).toBe(firstKey);

    await act(async () => {
      await result.current.retry();
    });
    expect(api.parkingApi.startParkingSession).toHaveBeenCalledTimes(2);
    expect((api.parkingApi.startParkingSession as jest.Mock).mock.calls[1][1]).toBe(firstKey);
    expect(result.current.attemptKey).toBeNull();
  });

  it('ACTIVE_PARKING_SESSION_EXISTS refetches active session and clears attempt', async () => {
    (api.parkingApi.startParkingSession as jest.Mock).mockRejectedValueOnce(
      new ConflictError({
        code: ACTIVE_PARKING_SESSION_EXISTS,
        message: 'already active',
        traceId: 't1',
        timestamp: '2026-07-21T09:00:00Z',
      }),
    );
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValueOnce(sessionFixture);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useStartParkingSession(createLocation()), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.start();
    });

    expect(api.parkingApi.getActiveParkingSession).toHaveBeenCalled();
    expect(client.getQueryData(parkingKeys.activeSession())).toEqual(sessionFixture);
    expect(result.current.phase).toBe('idle');
    expect(result.current.attemptKey).toBeNull();
  });

  it('conclusive rejection clears key so next attempt is fresh', async () => {
    (api.parkingApi.startParkingSession as jest.Mock)
      .mockRejectedValueOnce(
        new ValidationError(400, { code: 'VALIDATION_ERROR', message: 'bad', traceId: 't', timestamp: '2026-07-21T09:00:00Z' }),
      )
      .mockResolvedValueOnce(sessionFixture);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useStartParkingSession(createLocation()), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.start();
    });
    expect(result.current.phase).toBe('rejected');
    const firstKey = (api.parkingApi.startParkingSession as jest.Mock).mock.calls[0][1];

    await act(async () => {
      await result.current.retry();
    });
    const secondKey = (api.parkingApi.startParkingSession as jest.Mock).mock.calls[1][1];
    expect(secondKey).not.toBe(firstKey);
  });

  it('blocks when offline before submission', async () => {
    useOnlineStatus.mockReturnValue(false);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useStartParkingSession(createLocation()), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.start();
    });

    expect(result.current.phase).toBe('offline');
    expect(api.parkingApi.startParkingSession).not.toHaveBeenCalled();
  });

  it('invalid coordinates fail closed without calling start', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const location = createLocation({
      position: { lat: Number.NaN, lng: 28 },
      refresh: jest.fn(async () => ({ lat: Number.NaN, lng: 28 })),
    });
    const { result } = renderHook(() => useStartParkingSession(location), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.start();
    });

    expect(result.current.phase).toBe('locationFailed');
    expect(api.parkingApi.startParkingSession).not.toHaveBeenCalled();
  });

  it('logout clears pending attempt key', async () => {
    (api.parkingApi.startParkingSession as jest.Mock).mockRejectedValueOnce(new NetworkError('x'));
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(null);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useStartParkingSession(createLocation()), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.start();
    });
    expect(result.current.attemptKey).toBeTruthy();

    await act(async () => {
      useAuthStore.getState().clearSession();
    });

    expect(result.current.attemptKey).toBeNull();
    expect(result.current.phase).toBe('idle');
  });

  it('permission denied without ask-again opens settings phase', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const location = createLocation({
      status: 'denied',
      canAskAgain: false,
      getCanAskAgain: () => false,
      request: jest.fn(async () => null),
      position: null,
    });
    const { result } = renderHook(() => useStartParkingSession(location), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.start();
    });

    expect(result.current.phase).toBe('permissionDeniedSettings');
    expect(api.parkingApi.startParkingSession).not.toHaveBeenCalled();
  });
});