import NetInfo from '@react-native-community/netinfo';
import { focusManager, onlineManager, useQueryClient } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react-native';
import { useEffect } from 'react';
import { AppState } from 'react-native';
import type { QueryClient } from '@tanstack/react-query';
import { meKeys } from '@/data/keys';
import { useAuthStore } from '@/state/authStore';
import { QueryProvider } from '../QueryProvider';
import { createMobileQueryClient, MOBILE_QUERY_CLIENT_POLICY } from '../query-client';

type NetInfoListener = (state: { isConnected: boolean | null }) => void;

const netInfoMock = NetInfo as unknown as {
  addEventListener: jest.Mock;
};

function QueryClientProbe({ onReady }: { onReady: (client: QueryClient) => void }) {
  const client = useQueryClient();
  useEffect(() => {
    onReady(client);
  }, [client, onReady]);
  return null;
}

describe('Task 8 mobile remount and reconnect', () => {
  let netInfoListeners: NetInfoListener[];
  let appStateHandler: ((status: string) => void) | null;
  let removeAppState: jest.Mock;

  beforeEach(() => {
    netInfoListeners = [];
    appStateHandler = null;
    removeAppState = jest.fn();
    onlineManager.setOnline(true);
    focusManager.setFocused(true);
    useAuthStore.setState({
      status: 'anonymous',
      user: null,
      sessionEpoch: 0,
    });

    netInfoMock.addEventListener.mockImplementation((listener: NetInfoListener) => {
      netInfoListeners.push(listener);
      return jest.fn();
    });

    jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, handler) => {
      appStateHandler = handler as (status: string) => void;
      return { remove: removeAppState } as { remove: () => void };
    });
  });

  afterEach(() => {
    jest.restoreAllMocks();
    onlineManager.setOnline(true);
    focusManager.setFocused(true);
  });

  it('documents reconnect/remount refetch policy', () => {
    expect(MOBILE_QUERY_CLIENT_POLICY.queries.refetchOnReconnect).toBe(true);
    expect(MOBILE_QUERY_CLIENT_POLICY.queries.refetchOnMount).toBe(true);
    expect(MOBILE_QUERY_CLIENT_POLICY.queries.networkMode).toBe('online');
  });

  it('creates one QueryClient per provider mount and clears AppState listeners on unmount', () => {
    const first = render(
      <QueryProvider>
        <></>
      </QueryProvider>,
    );
    expect(netInfoListeners.length).toBeGreaterThanOrEqual(1);
    expect(AppState.addEventListener).toHaveBeenCalled();
    const listenersAfterFirst = netInfoListeners.length;

    first.unmount();
    expect(removeAppState).toHaveBeenCalled();

    const second = render(
      <QueryProvider>
        <></>
      </QueryProvider>,
    );
    expect(netInfoListeners.length).toBeGreaterThan(listenersAfterFirst);
    second.unmount();
  });

  it('wires NetInfo reconnect into onlineManager', () => {
    const view = render(
      <QueryProvider>
        <></>
      </QueryProvider>,
    );

    act(() => {
      netInfoListeners[0]?.({ isConnected: false });
    });
    expect(onlineManager.isOnline()).toBe(false);

    act(() => {
      netInfoListeners[0]?.({ isConnected: true });
    });
    expect(onlineManager.isOnline()).toBe(true);

    view.unmount();
  });

  it('wires AppState focus changes into focusManager', () => {
    const view = render(
      <QueryProvider>
        <></>
      </QueryProvider>,
    );

    act(() => {
      appStateHandler?.('background');
    });
    expect(focusManager.isFocused()).toBe(false);

    act(() => {
      appStateHandler?.('active');
    });
    expect(focusManager.isFocused()).toBe(true);

    view.unmount();
  });

  it('deduplicates concurrent fetches for the same key', async () => {
    const client = createMobileQueryClient();
    let starts = 0;
    const queryFn = jest.fn(async () => {
      starts += 1;
      await new Promise((resolve) => setTimeout(resolve, 30));
      return { id: 'once' };
    });

    const [a, b] = await Promise.all([
      client.fetchQuery({ queryKey: meKeys.profile(), queryFn }),
      client.fetchQuery({ queryKey: meKeys.profile(), queryFn }),
    ]);

    expect(a).toEqual(b);
    expect(starts).toBe(1);
    expect(queryFn).toHaveBeenCalledTimes(1);
  });

  it('invalidate + refetch restores canonical server state over stale local cache', async () => {
    const client = createMobileQueryClient();
    client.setQueryData(meKeys.profile(), { id: 'stale' });

    let version = 0;
    const queryFn = jest.fn(async () => {
      version += 1;
      return { id: `server-${version}` };
    });

    await client.fetchQuery({ queryKey: meKeys.profile(), queryFn, staleTime: 0 });
    expect(client.getQueryData(meKeys.profile())).toEqual({ id: 'server-1' });

    await client.invalidateQueries({ queryKey: meKeys.profile() });
    await client.fetchQuery({ queryKey: meKeys.profile(), queryFn, staleTime: 0 });
    expect(queryFn).toHaveBeenCalledTimes(2);
    expect(client.getQueryData(meKeys.profile())).toEqual({ id: 'server-2' });
  });

  it('session identity change clears user cache and reconnect does not resurrect it', async () => {
    let captured: QueryClient | null = null;
    const view = render(
      <QueryProvider>
        <QueryClientProbe
          onReady={(client) => {
            captured = client;
          }}
        />
      </QueryProvider>,
    );

    await waitFor(() => {
      expect(captured).toBeTruthy();
    });

    act(() => {
      useAuthStore.getState().setSession({
        id: 'user-a',
        email: 'a@parkio.dev',
        roles: ['USER'],
        status: 'ACTIVE',
      });
    });

    await waitFor(() => {
      // Identity transition from anonymous → user-a already ran clear; seed after settle.
      expect(useAuthStore.getState().user?.id).toBe('user-a');
    });

    act(() => {
      captured!.setQueryData(meKeys.profile(), { id: 'profile-a' });
    });
    expect(captured!.getQueryData(meKeys.profile())).toEqual({ id: 'profile-a' });

    act(() => {
      useAuthStore.getState().clearSession();
      netInfoListeners[netInfoListeners.length - 1]?.({ isConnected: false });
      netInfoListeners[netInfoListeners.length - 1]?.({ isConnected: true });
    });

    await waitFor(() => {
      expect(captured!.getQueryData(meKeys.profile())).toBeUndefined();
    });

    view.unmount();
  });
});
