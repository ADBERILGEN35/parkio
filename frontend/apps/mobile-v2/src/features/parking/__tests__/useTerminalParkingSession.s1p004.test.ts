import { act, renderHook, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';
import { ConflictError, NetworkError, NotFoundError, ValidationError } from '@parkio/api-client';
import { parkingKeys } from '@/data/keys';
import { useAuthStore } from '@/state/authStore';
import {
  PARKING_SESSION_NOT_ACTIVE,
  PARKING_SESSION_NOT_FOUND,
  useTerminalParkingSession,
} from '../useTerminalParkingSession';
import * as api from '@/services/api';

jest.mock('@/services/api', () => ({
  parkingApi: {
    completeParkingSession: jest.fn(),
    cancelParkingSession: jest.fn(),
    getActiveParkingSession: jest.fn(),
  },
}));

const SESSION_ID = 'd431ad5a-f8ce-4be2-b4dc-248b47990b39';

const activeSession = {
  id: SESSION_ID,
  status: 'ACTIVE' as const,
  parkingSource: 'MANUAL' as const,
  startedAt: '2026-07-21T09:00:00Z',
  endedAt: null,
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: null,
};

const completedSession = {
  ...activeSession,
  status: 'COMPLETED' as const,
  endedAt: '2026-07-21T10:00:00Z',
};

const cancelledSession = {
  ...activeSession,
  status: 'CANCELLED' as const,
  endedAt: '2026-07-21T10:00:00Z',
};

function wrapperFor(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children);
  };
}

describe('useTerminalParkingSession S1-P0-04', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useAuthStore.setState({
      status: 'authenticated',
      user: { id: 'user-1', email: 'a@b.c', displayName: 'A', roles: ['USER'] } as never,
      sessionEpoch: 1,
    });
  });

  it('complete calls API with sessionId and caller key, then clears active cache', async () => {
    (api.parkingApi.completeParkingSession as jest.Mock).mockResolvedValueOnce(completedSession);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(parkingKeys.activeSession(), activeSession);

    const { result } = renderHook(() => useTerminalParkingSession(SESSION_ID), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.complete(SESSION_ID);
    });

    expect(api.parkingApi.completeParkingSession).toHaveBeenCalledTimes(1);
    const [id, key] = (api.parkingApi.completeParkingSession as jest.Mock).mock.calls[0];
    expect(id).toBe(SESSION_ID);
    expect(typeof key).toBe('string');
    expect(key.length).toBeGreaterThan(8);
    expect(api.parkingApi.cancelParkingSession).not.toHaveBeenCalled();
    expect(client.getQueryData(parkingKeys.activeSession())).toBeNull();
    expect(result.current.phase).toBe('idle');
    expect(result.current.completeKey).toBeNull();
  });

  it('cancel calls cancel API with its own key and clears active cache', async () => {
    (api.parkingApi.cancelParkingSession as jest.Mock).mockResolvedValueOnce(cancelledSession);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(parkingKeys.activeSession(), activeSession);

    const { result } = renderHook(() => useTerminalParkingSession(SESSION_ID), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.cancel(SESSION_ID);
    });

    expect(api.parkingApi.cancelParkingSession).toHaveBeenCalledTimes(1);
    expect(api.parkingApi.completeParkingSession).not.toHaveBeenCalled();
    expect(client.getQueryData(parkingKeys.activeSession())).toBeNull();
  });

  it('double complete while in flight produces one mutation', async () => {
    let resolveComplete!: (value: typeof completedSession) => void;
    (api.parkingApi.completeParkingSession as jest.Mock).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveComplete = resolve;
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useTerminalParkingSession(SESSION_ID), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      const p1 = result.current.complete(SESSION_ID);
      const p2 = result.current.complete(SESSION_ID);
      resolveComplete(completedSession);
      await Promise.all([p1, p2]);
    });

    expect(api.parkingApi.completeParkingSession).toHaveBeenCalledTimes(1);
  });

  it('complete in flight blocks cancel (mutual exclusion)', async () => {
    let resolveComplete!: (value: typeof completedSession) => void;
    (api.parkingApi.completeParkingSession as jest.Mock).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveComplete = resolve;
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useTerminalParkingSession(SESSION_ID), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      const p1 = result.current.complete(SESSION_ID);
      const p2 = result.current.cancel(SESSION_ID);
      resolveComplete(completedSession);
      await Promise.all([p1, p2]);
    });

    expect(api.parkingApi.completeParkingSession).toHaveBeenCalledTimes(1);
    expect(api.parkingApi.cancelParkingSession).not.toHaveBeenCalled();
  });

  it('ambiguous complete keeps key, reconciles, and retries with same key', async () => {
    (api.parkingApi.completeParkingSession as jest.Mock)
      .mockRejectedValueOnce(new NetworkError('timeout-ish'))
      .mockResolvedValueOnce(completedSession);
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(activeSession);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(parkingKeys.activeSession(), activeSession);

    const { result } = renderHook(() => useTerminalParkingSession(SESSION_ID), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.complete(SESSION_ID);
    });
    expect(result.current.phase).toBe('ambiguousComplete');
    const firstKey = (api.parkingApi.completeParkingSession as jest.Mock).mock.calls[0][1];
    expect(result.current.completeKey).toBe(firstKey);
    expect(result.current.cancelKey).toBeNull();

    await act(async () => {
      await result.current.retry();
    });
    expect(api.parkingApi.completeParkingSession).toHaveBeenCalledTimes(2);
    expect((api.parkingApi.completeParkingSession as jest.Mock).mock.calls[1][1]).toBe(firstKey);
    expect(client.getQueryData(parkingKeys.activeSession())).toBeNull();
  });

  it('ambiguous complete with no active session treats as reconciled success', async () => {
    (api.parkingApi.completeParkingSession as jest.Mock).mockRejectedValueOnce(
      new NetworkError('gone'),
    );
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValueOnce(null);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(parkingKeys.activeSession(), activeSession);

    const { result } = renderHook(() => useTerminalParkingSession(SESSION_ID), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.complete(SESSION_ID);
    });

    expect(client.getQueryData(parkingKeys.activeSession())).toBeNull();
    expect(result.current.phase).toBe('idle');
    expect(result.current.completeKey).toBeNull();
  });

  it('ambiguous cancel keeps cancel key and blocks complete retry path', async () => {
    (api.parkingApi.cancelParkingSession as jest.Mock)
      .mockRejectedValueOnce(new NetworkError('cut'))
      .mockResolvedValueOnce(cancelledSession);
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(activeSession);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    const { result } = renderHook(() => useTerminalParkingSession(SESSION_ID), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.cancel(SESSION_ID);
    });
    expect(result.current.phase).toBe('ambiguousCancel');
    const cancelKey = (api.parkingApi.cancelParkingSession as jest.Mock).mock.calls[0][1];
    expect(result.current.cancelKey).toBe(cancelKey);
    expect(result.current.completeKey).toBeNull();
    expect(result.current.operation).toBe('cancel');

    await act(async () => {
      await result.current.retry();
    });
    expect(api.parkingApi.cancelParkingSession).toHaveBeenCalledTimes(2);
    expect((api.parkingApi.cancelParkingSession as jest.Mock).mock.calls[1][1]).toBe(cancelKey);
    expect(api.parkingApi.completeParkingSession).not.toHaveBeenCalled();
  });

  it('PARKING_SESSION_NOT_ACTIVE with null active converges to cleared banner', async () => {
    (api.parkingApi.completeParkingSession as jest.Mock).mockRejectedValueOnce(
      new ConflictError({
        code: PARKING_SESSION_NOT_ACTIVE,
        message: 'not active',
        traceId: 't1',
        timestamp: '2026-07-21T09:00:00Z',
      }),
    );
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValueOnce(null);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(parkingKeys.activeSession(), activeSession);

    const { result } = renderHook(() => useTerminalParkingSession(SESSION_ID), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.complete(SESSION_ID);
    });

    expect(client.getQueryData(parkingKeys.activeSession())).toBeNull();
    expect(result.current.phase).toBe('idle');
  });

  it('different active session after conflict is preserved', async () => {
    const other = { ...activeSession, id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' };
    (api.parkingApi.completeParkingSession as jest.Mock).mockRejectedValueOnce(
      new NotFoundError({
        code: PARKING_SESSION_NOT_FOUND,
        message: 'missing',
        traceId: 't2',
        timestamp: '2026-07-21T09:00:00Z',
      }),
    );
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValueOnce(other);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(parkingKeys.activeSession(), activeSession);

    const { result } = renderHook(() => useTerminalParkingSession(SESSION_ID), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.complete(SESSION_ID);
    });

    // fetchQuery applies other into cache; we must not null it out as the old session.
    await waitFor(() => {
      expect(client.getQueryData(parkingKeys.activeSession())).toEqual(other);
    });
    expect(result.current.phase).toBe('idle');
  });

  it('user switch clears terminal attempt keys', async () => {
    (api.parkingApi.completeParkingSession as jest.Mock).mockRejectedValueOnce(
      new NetworkError('cut'),
    );
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(activeSession);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    const { result, rerender } = renderHook(
      ({ sid }: { sid: string }) => useTerminalParkingSession(sid),
      { initialProps: { sid: SESSION_ID }, wrapper: wrapperFor(client) },
    );

    await act(async () => {
      await result.current.complete(SESSION_ID);
    });
    expect(result.current.completeKey).toBeTruthy();

    act(() => {
      useAuthStore.setState({
        status: 'authenticated',
        user: { id: 'user-2', email: 'c@d.e', displayName: 'B', roles: ['USER'] } as never,
        sessionEpoch: 2,
      });
    });
    rerender({ sid: SESSION_ID });

    expect(result.current.completeKey).toBeNull();
    expect(result.current.phase).toBe('idle');
  });

  it('sessionId change clears stale terminal attempt', async () => {
    (api.parkingApi.completeParkingSession as jest.Mock).mockRejectedValueOnce(
      new NetworkError('cut'),
    );
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(activeSession);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const otherId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

    const { result, rerender } = renderHook(
      ({ sid }: { sid: string | null }) => useTerminalParkingSession(sid),
      { initialProps: { sid: SESSION_ID as string | null }, wrapper: wrapperFor(client) },
    );

    await act(async () => {
      await result.current.complete(SESSION_ID);
    });
    expect(result.current.completeKey).toBeTruthy();

    rerender({ sid: otherId });
    expect(result.current.completeKey).toBeNull();
    expect(result.current.phase).toBe('idle');
  });

  it('conclusive rejection drops key so next retry is a new attempt', async () => {
    (api.parkingApi.completeParkingSession as jest.Mock)
      .mockRejectedValueOnce(
        new ValidationError(400, {
          code: 'VALIDATION_ERROR',
          message: 'bad',
          traceId: 't',
          timestamp: '2026-07-21T09:00:00Z',
        }),
      )
      .mockResolvedValueOnce(completedSession);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useTerminalParkingSession(SESSION_ID), {
      wrapper: wrapperFor(client),
    });

    await act(async () => {
      await result.current.complete(SESSION_ID);
    });
    expect(result.current.phase).toBe('rejected');
    const firstKey = (api.parkingApi.completeParkingSession as jest.Mock).mock.calls[0][1];
    expect(result.current.completeKey).toBeNull();

    await act(async () => {
      await result.current.retry();
    });
    const secondKey = (api.parkingApi.completeParkingSession as jest.Mock).mock.calls[1][1];
    expect(secondKey).not.toBe(firstKey);
  });

  it('late complete from previous user does not clear new user cache', async () => {
    let resolveComplete!: (value: typeof completedSession) => void;
    (api.parkingApi.completeParkingSession as jest.Mock).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveComplete = resolve;
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(parkingKeys.activeSession(), activeSession);

    const { result } = renderHook(() => useTerminalParkingSession(SESSION_ID), {
      wrapper: wrapperFor(client),
    });

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.complete(SESSION_ID);
    });

    act(() => {
      useAuthStore.setState({
        status: 'authenticated',
        user: { id: 'user-2', email: 'c@d.e', displayName: 'B', roles: ['USER'] } as never,
        sessionEpoch: 2,
      });
      client.setQueryData(parkingKeys.activeSession(), {
        ...activeSession,
        id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
      });
    });

    await act(async () => {
      resolveComplete(completedSession);
      await pending;
    });

    expect(client.getQueryData(parkingKeys.activeSession())).toEqual({
      ...activeSession,
      id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    });
  });
});