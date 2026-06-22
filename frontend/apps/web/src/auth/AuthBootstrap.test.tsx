import { setRefreshHandler } from '@parkio/api-client';
import { render, waitFor } from '@testing-library/react';
import { StrictMode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthBootstrap } from './AuthBootstrap';
import { useAuthStore } from './store';

function resetStore(overrides: Partial<ReturnType<typeof useAuthStore.getState>> = {}) {
  useAuthStore.setState({
    accessToken: null,
    user: null,
    roles: [],
    status: null,
    isAuthenticated: false,
    suspended: false,
    provisioning: false,
    bootstrapPending: true,
    sessionEpoch: 0,
    ...overrides,
  });
}

afterEach(() => setRefreshHandler(null));

describe('AuthBootstrap', () => {
  it('sends exactly one refresh under StrictMode double-invocation', async () => {
    resetStore();
    const refresh = vi.fn(async () => 'access-token');
    setRefreshHandler(refresh);

    render(
      <StrictMode>
        <AuthBootstrap />
      </StrictMode>,
    );

    await waitFor(() => expect(useAuthStore.getState().bootstrapPending).toBe(false));
    // Single-flight collapses the StrictMode double effect into one request.
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('does not refresh when a session already exists', async () => {
    resetStore({ accessToken: 'existing', isAuthenticated: true });
    const refresh = vi.fn(async () => 'unexpected');
    setRefreshHandler(refresh);

    render(
      <StrictMode>
        <AuthBootstrap />
      </StrictMode>,
    );

    await waitFor(() => expect(useAuthStore.getState().bootstrapPending).toBe(false));
    expect(refresh).not.toHaveBeenCalled();
  });

  it('ends bootstrap even when refresh fails (so guards stop loading)', async () => {
    resetStore();
    setRefreshHandler(vi.fn(async () => null));

    render(
      <StrictMode>
        <AuthBootstrap />
      </StrictMode>,
    );

    await waitFor(() => expect(useAuthStore.getState().bootstrapPending).toBe(false));
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });
});
