import type { ParkingSessionResponse } from '@parkio/types';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { WebAppRuntime } from '@/app/runtime';
import { parkingKeys } from '@/data/keys';
import * as toast from '@/lib/toast';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import {
  createTestAppRuntime,
  renderWithProviders as renderWithBaseProviders,
  signInAs,
} from '@/test/utils';
import {
  ActiveParkingSessionCard,
  ActiveParkingSessionErrorCard,
} from './ActiveParkingSessionCard';
import * as openMaps from './openParkingMaps';
import * as shareLoc from './shareParkingLocation';

let runtime: WebAppRuntime;

function renderCard(
  session: ParkingSessionResponse,
  onFocusCar = vi.fn(),
) {
  return {
    onFocusCar,
    ...renderWithBaseProviders(
      <ActiveParkingSessionCard session={session} onFocusCar={onFocusCar} />,
      { runtime },
    ),
  };
}

const session: ParkingSessionResponse = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  status: 'ACTIVE',
  parkingSource: 'MANUAL',
  startedAt: '2026-07-25T10:00:00.000Z',
  endedAt: null,
  latitude: 38.42,
  longitude: 27.14,
  estimatedFee: null,
};

const completed: ParkingSessionResponse = {
  ...session,
  status: 'COMPLETED',
  endedAt: '2026-07-25T11:00:00.000Z',
};

const cancelled: ParkingSessionResponse = {
  ...session,
  status: 'CANCELLED',
  endedAt: '2026-07-25T11:00:00.000Z',
};

beforeEach(() => {
  runtime = createTestAppRuntime();
  signInAs(runtime, ['USER']);
  vi.spyOn(toast, 'showSuccess').mockImplementation(() => undefined);
  vi.spyOn(toast, 'showError').mockImplementation(() => undefined);
  vi.spyOn(toast, 'showInfo').mockImplementation(() => undefined);
});

describe('ActiveParkingSessionCard', () => {
  it('renders title/source/elapsed and Find my car uses shared focus without exposing ids/coords', async () => {
    const { onFocusCar } = renderCard(session);
    const user = userEvent.setup();

    expect(screen.getByTestId('active-parking-session-card')).toBeInTheDocument();
    expect(screen.getByText('Parked')).toBeInTheDocument();
    expect(screen.getByTestId('active-parking-source')).toHaveTextContent('Saved by you');
    expect(screen.queryByText(session.id)).not.toBeInTheDocument();
    expect(screen.queryByText(/38\.42/)).not.toBeInTheDocument();

    await user.click(screen.getByTestId('active-parking-find-my-car'));
    expect(onFocusCar).toHaveBeenCalledOnce();
  });

  it('opens maps through the safe helper and never embeds the session id', async () => {
    const spy = vi.spyOn(openMaps, 'openParkingLocationInMaps').mockReturnValue(true);
    renderCard(session);
    const user = userEvent.setup();

    await user.click(screen.getByTestId('active-parking-open-maps'));
    expect(spy).toHaveBeenCalledWith(38.42, 27.14);
  });

  it('disables Open in Maps for invalid coordinates', () => {
    renderCard({ ...session, latitude: Number.NaN });
    expect(screen.getByTestId('active-parking-open-maps')).toBeDisabled();
    expect(screen.getByTestId('active-parking-share')).toBeDisabled();
  });

  it('shares via helper and toasts clipboard success', async () => {
    vi.spyOn(shareLoc, 'shareParkingLocation').mockResolvedValue({
      ok: true,
      method: 'clipboard',
    });
    renderCard(session);
    const user = userEvent.setup();

    await user.click(screen.getByTestId('active-parking-share'));
    await waitFor(() => {
      expect(toast.showSuccess).toHaveBeenCalledWith('Map link copied to clipboard.');
    });
  });

  it('does not toast an error when native share is cancelled', async () => {
    vi.spyOn(shareLoc, 'shareParkingLocation').mockResolvedValue({
      ok: false,
      reason: 'cancelled',
    });
    renderCard(session);
    const user = userEvent.setup();

    await user.click(screen.getByTestId('active-parking-share'));
    await waitFor(() => {
      expect(shareLoc.shareParkingLocation).toHaveBeenCalled();
    });
    expect(toast.showError).not.toHaveBeenCalled();
  });

  it('opens inline completion confirmation and Keep session closes without mutating', async () => {
    let completeCalls = 0;
    server.use(
      http.post(`${API_BASE}/parking/sessions/${session.id}/complete`, () => {
        completeCalls += 1;
        return HttpResponse.json(completed);
      }),
    );
    renderCard(session);
    const user = userEvent.setup();

    await user.click(screen.getByTestId('active-parking-leave'));
    expect(screen.getByTestId('active-parking-complete-confirm')).toBeInTheDocument();
    expect(screen.queryByTestId('active-parking-cancel-confirm')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('active-parking-keep-session'));
    expect(screen.queryByTestId('active-parking-complete-confirm')).not.toBeInTheDocument();
    expect(completeCalls).toBe(0);
  });

  it('completes exactly once and writes null into the active cache', async () => {
    let completeCalls = 0;
    server.use(
      http.post(`${API_BASE}/parking/sessions/${session.id}/complete`, async () => {
        completeCalls += 1;
        await delay(50);
        return HttpResponse.json(completed);
      }),
    );
    const { queryClient } = renderCard(session);
    queryClient.setQueryData(parkingKeys.activeSession(), session);
    const user = userEvent.setup();

    await user.click(screen.getByTestId('active-parking-leave'));
    await user.click(screen.getByTestId('active-parking-confirm-leave'));
    await user.click(screen.getByTestId('active-parking-confirm-leave'));

    await waitFor(() => {
      expect(toast.showSuccess).toHaveBeenCalledWith('Parking session finished.');
    });
    expect(completeCalls).toBe(1);
    expect(queryClient.getQueryData(parkingKeys.activeSession())).toBeNull();
  });

  it('preserves active UI when complete unexpectedly returns ACTIVE', async () => {
    server.use(
      http.post(`${API_BASE}/parking/sessions/${session.id}/complete`, () =>
        HttpResponse.json(session),
      ),
    );
    const { queryClient } = renderCard(session);
    queryClient.setQueryData(parkingKeys.activeSession(), session);
    const user = userEvent.setup();

    await user.click(screen.getByTestId('active-parking-leave'));
    await user.click(screen.getByTestId('active-parking-confirm-leave'));

    await waitFor(() => {
      expect(toast.showInfo).toHaveBeenCalledWith('Your parking session is still active.');
    });
    expect(queryClient.getQueryData(parkingKeys.activeSession())).toEqual(session);
    expect(screen.getByTestId('active-parking-session-card')).toBeInTheDocument();
  });

  it('reconciles a stale not-active conflict by clearing active state', async () => {
    server.use(
      http.post(`${API_BASE}/parking/sessions/${session.id}/complete`, () =>
        HttpResponse.json(apiErrorBody('PARKING_SESSION_NOT_ACTIVE', 'gone'), { status: 409 }),
      ),
      http.get(`${API_BASE}/parking/sessions/active`, () => new HttpResponse(null, { status: 204 })),
    );
    const { queryClient } = renderCard(session);
    queryClient.setQueryData(parkingKeys.activeSession(), session);
    const user = userEvent.setup();

    await user.click(screen.getByTestId('active-parking-leave'));
    await user.click(screen.getByTestId('active-parking-confirm-leave'));

    await waitFor(() => {
      expect(toast.showInfo).toHaveBeenCalledWith('That parking session was already ended.');
    });
    expect(queryClient.getQueryData(parkingKeys.activeSession())).toBeNull();
  });

  it('refetches active state on ambiguous transport and clears when gone', async () => {
    // Force the mutation transport into NetworkError by aborting before response.
    server.use(
      http.post(`${API_BASE}/parking/sessions/${session.id}/complete`, () => {
        return HttpResponse.error();
      }),
      http.get(`${API_BASE}/parking/sessions/active`, () => new HttpResponse(null, { status: 204 })),
    );
    const { queryClient } = renderCard(session);
    queryClient.setQueryData(parkingKeys.activeSession(), session);
    const user = userEvent.setup();

    await user.click(screen.getByTestId('active-parking-leave'));
    await user.click(screen.getByTestId('active-parking-confirm-leave'));

    await waitFor(() => {
      expect(toast.showSuccess).toHaveBeenCalledWith('Parking session finished.');
    });
    expect(queryClient.getQueryData(parkingKeys.activeSession())).toBeNull();
  });

  it('cancels with distinct confirmation and clears active state once', async () => {
    let cancelCalls = 0;
    server.use(
      http.post(`${API_BASE}/parking/sessions/${session.id}/cancel`, async () => {
        cancelCalls += 1;
        await delay(40);
        return HttpResponse.json(cancelled);
      }),
    );
    const { queryClient } = renderCard(session);
    queryClient.setQueryData(parkingKeys.activeSession(), session);
    const user = userEvent.setup();

    await user.click(screen.getByTestId('active-parking-cancel'));
    expect(screen.getByTestId('active-parking-cancel-confirm')).toBeInTheDocument();
    expect(screen.getByText(/started this parking session by mistake/i)).toBeInTheDocument();
    expect(screen.queryByTestId('active-parking-complete-confirm')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('active-parking-confirm-cancel'));
    await user.click(screen.getByTestId('active-parking-confirm-cancel'));

    await waitFor(() => {
      expect(toast.showSuccess).toHaveBeenCalledWith('Parking session cancelled.');
    });
    expect(cancelCalls).toBe(1);
    expect(queryClient.getQueryData(parkingKeys.activeSession())).toBeNull();
  });

  it('keeps one confirmation open and hides the sibling terminal CTA', async () => {
    renderCard(session);
    const user = userEvent.setup();

    await user.click(screen.getByTestId('active-parking-leave'));
    expect(screen.getByTestId('active-parking-complete-confirm')).toBeInTheDocument();
    expect(screen.queryByTestId('active-parking-cancel')).not.toBeInTheDocument();
    expect(screen.queryByTestId('active-parking-open-maps')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('active-parking-keep-session'));
    expect(screen.queryByTestId('active-parking-complete-confirm')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('active-parking-cancel'));
    expect(screen.getByTestId('active-parking-cancel-confirm')).toBeInTheDocument();
    expect(screen.queryByTestId('active-parking-leave')).not.toBeInTheDocument();
  });

  it('disables Find my car when coordinates are unusable', () => {
    renderCard({ ...session, latitude: Number.NaN });
    expect(screen.getByTestId('active-parking-find-my-car')).toBeDisabled();
    expect(screen.getByTestId('active-parking-open-maps')).toBeDisabled();
    expect(screen.getByTestId('active-parking-share')).toBeDisabled();
  });

  it('resets confirmation when the session id changes', async () => {
    function Harness() {
      const [id, setId] = useState(session.id);
      return (
        <>
          <button
            type="button"
            data-testid="swap-session-id"
            onClick={() => setId('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')}
          >
            swap
          </button>
          <ActiveParkingSessionCard
            session={{ ...session, id }}
            onFocusCar={vi.fn()}
          />
        </>
      );
    }

    renderWithBaseProviders(<Harness />, { runtime });
    const user = userEvent.setup();

    await user.click(screen.getByTestId('active-parking-leave'));
    expect(screen.getByTestId('active-parking-complete-confirm')).toBeInTheDocument();

    await user.click(screen.getByTestId('swap-session-id'));

    expect(screen.queryByTestId('active-parking-complete-confirm')).not.toBeInTheDocument();
    expect(screen.getByTestId('active-parking-leave')).toBeInTheDocument();
  });

  it('supports the same action lifecycle for COMMUNITY sessions', async () => {
    server.use(
      http.post(`${API_BASE}/parking/sessions/${session.id}/complete`, () =>
        HttpResponse.json(completed),
      ),
    );
    const community = { ...session, parkingSource: 'COMMUNITY' as const };
    const { queryClient } = renderCard(community);
    queryClient.setQueryData(parkingKeys.activeSession(), community);
    const user = userEvent.setup();

    expect(screen.getByTestId('active-parking-source')).toHaveTextContent('From a claimed spot');
    await user.click(screen.getByTestId('active-parking-leave'));
    await user.click(screen.getByTestId('active-parking-confirm-leave'));

    await waitFor(() => {
      expect(toast.showSuccess).toHaveBeenCalledWith('Parking session finished.');
    });
  });

  it('renders a recoverable error strip with retry', async () => {
    const onRetry = vi.fn();
    renderWithBaseProviders(<ActiveParkingSessionErrorCard onRetry={onRetry} />, { runtime });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /try again/i }));
    expect(onRetry).toHaveBeenCalledOnce();
  });
});
