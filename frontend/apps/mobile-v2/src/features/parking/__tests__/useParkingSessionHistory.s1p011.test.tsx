import { act, renderHook, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConflictError } from '@parkio/api-client';
import type { ReactNode } from 'react';
import { parkingKeys } from '@/data/keys';
import { useDeleteParkingSessionActions } from '../useParkingSessionHistory';
import { useAuthStore } from '@/state/authStore';
import * as api from '@/services/api';

const mockCompleted = {
  id: 'd431ad5a-f8ce-4be2-b4dc-248b47990b39',
  status: 'COMPLETED' as const,
  parkingSource: 'MANUAL' as const,
  startedAt: '2026-07-21T09:00:00Z',
  endedAt: '2026-07-21T10:00:00Z',
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: null,
  lastConfirmedAt: '2026-07-25T10:00:00.000Z',
  completionType: null,
};

const mockActive = {
  ...mockCompleted,
  id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  status: 'ACTIVE' as const,
  endedAt: null,
};

const mockToastShow = jest.fn();

jest.mock('@/services/api', () => ({
  parkingApi: {
    getParkingSessionHistory: jest.fn(async () => ({
      items: [mockCompleted],
      nextCursor: null,
    })),
    deleteParkingSession: jest.fn(async () => undefined),
    deleteParkingSessionHistory: jest.fn(async () => undefined),
    getActiveParkingSession: jest.fn(async () => mockActive),
  },
}));

jest.mock('@/hooks/useOnlineStatus', () => ({
  useOnlineStatus: jest.fn(() => true),
}));

jest.mock('@/providers/ToastProvider', () => ({
  useToast: () => ({ show: mockToastShow }),
}));

jest.mock('@/i18n/LocaleProvider', () => ({
  useT: () => (key: string) => key,
  useLocale: () => ({ locale: 'en' as const }),
}));

import { useOnlineStatus } from '@/hooks/useOnlineStatus';

function wrapperFor(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe('S1-P0-11 useDeleteParkingSessionActions', () => {
  let client: QueryClient;

  beforeEach(() => {
    jest.clearAllMocks();
    client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    mockToastShow.mockClear();
    (useOnlineStatus as jest.Mock).mockReturnValue(true);
    useAuthStore.setState({
      status: 'authenticated',
      user: { id: 'user-a', email: 'a@parkio.dev', roles: [] },
      sessionEpoch: 1,
      accessToken: 't',
    } as never);
    (api.parkingApi.getParkingSessionHistory as jest.Mock).mockResolvedValue({
      items: [mockCompleted],
      nextCursor: null,
    });
    (api.parkingApi.deleteParkingSession as jest.Mock).mockResolvedValue(undefined);
    (api.parkingApi.deleteParkingSessionHistory as jest.Mock).mockResolvedValue(undefined);
  });

  it('loads terminal history and filters ACTIVE if present', async () => {
    (api.parkingApi.getParkingSessionHistory as jest.Mock).mockResolvedValueOnce({
      items: [mockActive, mockCompleted],
      nextCursor: null,
    });
    const { result } = renderHook(() => useDeleteParkingSessionActions(), {
      wrapper: wrapperFor(client),
    });
    await waitFor(() => expect(result.current.items).toHaveLength(1));
    expect(result.current.items[0]?.status).toBe('COMPLETED');
  });

  it('confirmed single delete calls API once and converges cache', async () => {
    client.setQueryData(parkingKeys.activeSession(), mockActive);
    const { result } = renderHook(() => useDeleteParkingSessionActions(), {
      wrapper: wrapperFor(client),
    });
    await waitFor(() => expect(result.current.items).toHaveLength(1));

    await act(async () => {
      await result.current.deleteSession(mockCompleted.id);
    });

    expect(api.parkingApi.deleteParkingSession).toHaveBeenCalledTimes(1);
    expect(api.parkingApi.deleteParkingSession).toHaveBeenCalledWith(mockCompleted.id);
    await waitFor(() => expect(result.current.items).toHaveLength(0));
    expect(client.getQueryData(parkingKeys.activeSession())).toEqual(mockActive);
  });

  it('blocks deletion when offline without calling API', async () => {
    (useOnlineStatus as jest.Mock).mockReturnValue(false);
    const { result } = renderHook(() => useDeleteParkingSessionActions(), {
      wrapper: wrapperFor(client),
    });
    await waitFor(() => expect(result.current.items.length).toBeGreaterThan(0));

    await act(async () => {
      await result.current.deleteSession(mockCompleted.id);
    });

    expect(api.parkingApi.deleteParkingSession).not.toHaveBeenCalled();
    expect(mockToastShow).toHaveBeenCalledWith('parkingSession.history.offline', 'error');
  });

  it('duplicate delete while pending calls API once', async () => {
    let resolveDelete: (() => void) | undefined;
    (api.parkingApi.deleteParkingSession as jest.Mock).mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          resolveDelete = resolve;
        }),
    );
    const { result } = renderHook(() => useDeleteParkingSessionActions(), {
      wrapper: wrapperFor(client),
    });
    await waitFor(() => expect(result.current.items).toHaveLength(1));

    let first!: Promise<void>;
    let second!: Promise<void>;
    act(() => {
      first = result.current.deleteSession(mockCompleted.id);
      second = result.current.deleteSession(mockCompleted.id);
    });
    await act(async () => {
      resolveDelete?.();
      await first;
      await second;
    });

    expect(api.parkingApi.deleteParkingSession).toHaveBeenCalledTimes(1);
  });

  it('409 reconciles active/history and does not pretend success', async () => {
    (api.parkingApi.deleteParkingSession as jest.Mock).mockRejectedValueOnce(
      new ConflictError({
        code: 'PARKING_SESSION_NOT_TERMINAL',
        message: 'ACTIVE',
        traceId: 't',
        timestamp: '2026-07-21T09:00:00Z',
      }),
    );
    const { result } = renderHook(() => useDeleteParkingSessionActions(), {
      wrapper: wrapperFor(client),
    });
    await waitFor(() => expect(result.current.items).toHaveLength(1));

    await act(async () => {
      await result.current.deleteSession(mockCompleted.id);
    });

    expect(mockToastShow).toHaveBeenCalledWith('parkingSession.history.delete.conflict', 'error');
    expect(api.parkingApi.getActiveParkingSession).toHaveBeenCalled();
  });

  it('delete-all uses one bulk request and preserves ACTIVE cache', async () => {
    client.setQueryData(parkingKeys.activeSession(), mockActive);
    const { result } = renderHook(() => useDeleteParkingSessionActions(), {
      wrapper: wrapperFor(client),
    });
    await waitFor(() => expect(result.current.items).toHaveLength(1));

    await act(async () => {
      await result.current.deleteAllHistory();
    });

    expect(api.parkingApi.deleteParkingSessionHistory).toHaveBeenCalledTimes(1);
    expect(api.parkingApi.deleteParkingSession).not.toHaveBeenCalled();
    expect(client.getQueryData(parkingKeys.activeSession())).toEqual(mockActive);
    await waitFor(() => expect(result.current.items).toHaveLength(0));
  });

  it('ignores stale delete completion after user switch', async () => {
    let resolveDelete: (() => void) | undefined;
    (api.parkingApi.deleteParkingSession as jest.Mock).mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          resolveDelete = resolve;
        }),
    );
    const { result } = renderHook(() => useDeleteParkingSessionActions(), {
      wrapper: wrapperFor(client),
    });
    await waitFor(() => expect(result.current.items).toHaveLength(1));

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.deleteSession(mockCompleted.id);
    });

    act(() => {
      useAuthStore.setState({
        status: 'authenticated',
        user: { id: 'user-b', email: 'b@parkio.dev', roles: [] },
        sessionEpoch: 2,
        accessToken: 't2',
      } as never);
    });

    await act(async () => {
      resolveDelete?.();
      await pending;
    });

    expect(mockToastShow).not.toHaveBeenCalledWith('parkingSession.history.delete.success', 'success');
  });
});
