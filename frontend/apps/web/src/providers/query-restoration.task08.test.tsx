import { QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRuntimeProvider } from '@/app/AppRuntimeProvider';
import { meKeys } from '@/data/keys';
import { SessionQueryCacheSync } from '@/data/SessionQueryCacheSync';
import { createTestAppRuntime, createTestQueryClient } from '@/test/utils';
import {
  WEB_QUERY_CLIENT_POLICY,
  createWebQueryClient,
} from './query-client';

/**
 * Task 8 — Web restoration / remount / reconnect verification.
 * Proves canonical server state wins over stale local cache without redesigning
 * QueryClient ownership or keys.
 */
describe('Task 8 web restoration', () => {
  let dispose: (() => void) | undefined;

  afterEach(() => {
    dispose?.();
    dispose = undefined;
    onlineManager.setOnline(true);
  });

  it('documents reconnect/remount refetch policy without redesign', () => {
    expect(WEB_QUERY_CLIENT_POLICY.queries.refetchOnReconnect).toBe(true);
    expect(WEB_QUERY_CLIENT_POLICY.queries.refetchOnMount).toBe(true);
    expect(WEB_QUERY_CLIENT_POLICY.queries.refetchOnWindowFocus).toBe(true);
    expect(WEB_QUERY_CLIENT_POLICY.queries.networkMode).toBe('online');
  });

  it('provider remount uses a fresh QueryClient and does not share stale cache', async () => {
    const first = createWebQueryClient();
    first.setQueryData(meKeys.profile(), { id: 'stale-local', displayName: 'Stale' });

    const second = createWebQueryClient();
    expect(second).not.toBe(first);
    expect(second.getQueryData(meKeys.profile())).toBeUndefined();

    // Fresh client fetch replaces any previous local value with server payload.
    let fetches = 0;
    await second.fetchQuery({
      queryKey: meKeys.profile(),
      staleTime: 0,
      queryFn: async () => {
        fetches += 1;
        return { id: 'server-fresh', displayName: 'Server' };
      },
    });
    expect(fetches).toBe(1);
    expect(second.getQueryData(meKeys.profile())).toEqual({
      id: 'server-fresh',
      displayName: 'Server',
    });
    expect(first.getQueryData(meKeys.profile())).toEqual({
      id: 'stale-local',
      displayName: 'Stale',
    });
  });

  it('invalidate + refetch restores canonical server state over stale cache', async () => {
    const client = createWebQueryClient();
    client.setQueryData(meKeys.profile(), { id: 'stale', displayName: 'Local' });

    let version = 0;
    const queryFn = vi.fn(async () => {
      version += 1;
      return { id: `server-${version}`, displayName: `Server ${version}` };
    });

    await client.fetchQuery({ queryKey: meKeys.profile(), queryFn, staleTime: 0 });
    expect(client.getQueryData(meKeys.profile())).toEqual({
      id: 'server-1',
      displayName: 'Server 1',
    });

    await client.invalidateQueries({ queryKey: meKeys.profile() });
    await client.fetchQuery({ queryKey: meKeys.profile(), queryFn, staleTime: 0 });
    expect(queryFn).toHaveBeenCalledTimes(2);
    expect(client.getQueryData(meKeys.profile())).toEqual({
      id: 'server-2',
      displayName: 'Server 2',
    });
  });

  it('deduplicates concurrent fetches for the same query key', async () => {
    const client = createTestQueryClient();
    let starts = 0;
    const queryFn = vi.fn(async () => {
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

  it('reconnect online transition does not resurrect cleared user cache', async () => {
    const queryClient = createTestQueryClient();
    const runtime = createTestAppRuntime(queryClient);
    dispose = () => runtime.dispose();

    const wrapper = ({ children }: { children: ReactNode }) => (
      <AppRuntimeProvider runtime={runtime}>
        <QueryClientProvider client={queryClient}>
          <SessionQueryCacheSync />
          {children}
        </QueryClientProvider>
      </AppRuntimeProvider>
    );

    render(<div>mount</div>, { wrapper });

    act(() => {
      runtime.authStore.getState().setSession('token-a', {
        id: 'user-a',
        email: 'a@parkio.dev',
        roles: ['USER'],
        status: 'ACTIVE',
        emailVerified: true,
      });
      queryClient.setQueryData(meKeys.profile(), { id: 'profile-a' });
    });

    act(() => {
      runtime.authStore.getState().clearSession();
      onlineManager.setOnline(false);
      onlineManager.setOnline(true);
    });

    await waitFor(() => {
      expect(queryClient.getQueryData(meKeys.profile())).toBeUndefined();
    });
  });
});
