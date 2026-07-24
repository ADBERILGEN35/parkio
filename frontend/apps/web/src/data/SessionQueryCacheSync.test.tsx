import type { Profile } from '@parkio/types';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { AppRuntimeProvider } from '@/app/AppRuntimeProvider';
import { createTestAppRuntime, createTestQueryClient } from '@/test/utils';
import { meKeys } from './keys';
import { SessionQueryCacheSync } from './SessionQueryCacheSync';

function createUser(id: string, email: string) {
  return {
    id,
    email,
    roles: ['USER'] as const,
    status: 'ACTIVE' as const,
    emailVerified: true,
  };
}

describe('SessionQueryCacheSync', () => {
  let dispose: (() => void) | undefined;

  afterEach(() => {
    dispose?.();
    dispose = undefined;
  });

  it('clears sensitive cache on logout and isolates sequential users', async () => {
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

    const profileA: Profile = {
      id: 'profile-a',
      authUserId: 'user-a',
      email: 'alice@parkio.dev',
      displayName: 'Alice',
      phoneNumber: null,
      city: null,
      status: 'ACTIVE',
      createdAt: '2026-01-01T00:00:00.000Z',
    };

    act(() => {
      runtime.authStore.getState().setSession('token-a', createUser('user-a', 'alice@parkio.dev'));
      queryClient.setQueryData(meKeys.profile(), profileA);
    });
    expect(queryClient.getQueryData(meKeys.profile())).toEqual(profileA);

    act(() => {
      runtime.authStore.getState().clearSession();
    });
    await waitFor(() => {
      expect(queryClient.getQueryData(meKeys.profile())).toBeUndefined();
    });

    const profileB: Profile = {
      ...profileA,
      id: 'profile-b',
      authUserId: 'user-b',
      email: 'bob@parkio.dev',
      displayName: 'Bob',
    };

    act(() => {
      runtime.authStore.getState().setSession('token-b', createUser('user-b', 'bob@parkio.dev'));
      queryClient.setQueryData(meKeys.profile(), profileB);
    });
    expect(queryClient.getQueryData(meKeys.profile())).toEqual(profileB);

    act(() => {
      runtime.authStore.getState().clearSession();
      runtime.authStore.getState().setSession('token-a', createUser('user-a', 'alice@parkio.dev'));
    });
    await waitFor(() => {
      expect(queryClient.getQueryData(meKeys.profile())).toBeUndefined();
    });
  });

  it('cancels in-flight authenticated work so late results cannot repopulate after logout', async () => {
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
      runtime.authStore.getState().setSession('token-a', createUser('user-a', 'alice@parkio.dev'));
    });

    let resolveFetch: (value: Profile) => void = () => undefined;
    const pending = new Promise<Profile>((resolve) => {
      resolveFetch = resolve;
    });

    const fetchPromise = queryClient
      .fetchQuery({
        queryKey: meKeys.profile(),
        queryFn: () => pending,
      })
      .then(
        () => 'resolved' as const,
        (error: unknown) => error,
      );

    act(() => {
      runtime.authStore.getState().clearSession();
    });

    await waitFor(() => {
      expect(queryClient.getQueryData(meKeys.profile())).toBeUndefined();
    });

    resolveFetch({
      id: 'late',
      authUserId: 'user-a',
      email: 'alice@parkio.dev',
      displayName: 'Late',
      phoneNumber: null,
      city: null,
      status: 'ACTIVE',
      createdAt: '2026-01-01T00:00:00.000Z',
    });

    const outcome = await fetchPromise;
    expect(outcome).not.toBe('resolved');
    expect(queryClient.getQueryData(meKeys.profile())).toBeUndefined();
  });
});
