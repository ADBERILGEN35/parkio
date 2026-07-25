import type { ParkingSessionResponse } from '@parkio/types';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
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
import { ParkHereStartControl } from './ParkHereStartControl';

let runtime: WebAppRuntime;

function renderControl() {
  return renderWithBaseProviders(<ParkHereStartControl />, { runtime });
}

function stubGeolocation(value: Partial<Geolocation> | undefined) {
  Object.defineProperty(navigator, 'geolocation', { configurable: true, value });
}

const createdSession: ParkingSessionResponse = {
  id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  status: 'ACTIVE',
  parkingSource: 'MANUAL',
  startedAt: '2026-07-25T12:00:00.000Z',
  endedAt: null,
  latitude: 38.42,
  longitude: 27.14,
  estimatedFee: null,
  lastConfirmedAt: '2026-07-25T10:00:00.000Z',
  completionType: null,
};

beforeEach(() => {
  runtime = createTestAppRuntime();
  signInAs(runtime, ['USER']);
  vi.spyOn(toast, 'showSuccess').mockImplementation(() => undefined);
  vi.spyOn(toast, 'showError').mockImplementation(() => undefined);
  vi.spyOn(toast, 'showInfo').mockImplementation(() => undefined);
  stubGeolocation({
    getCurrentPosition: (success) =>
      success({ coords: { latitude: 38.42, longitude: 27.14 } } as GeolocationPosition),
  });
});

describe('ParkHereStartControl', () => {
  it('starts a session, toasts success, and writes ACTIVE into the cache', async () => {
    let startCalls = 0;
    server.use(
      http.post(`${API_BASE}/parking/sessions`, async ({ request }) => {
        startCalls += 1;
        expect(request.headers.get('Idempotency-Key')).toBeTruthy();
        const body = (await request.json()) as { latitude: number; longitude: number };
        expect(body).toEqual({ latitude: 38.42, longitude: 27.14 });
        return HttpResponse.json(createdSession, { status: 201 });
      }),
    );

    const { queryClient } = renderControl();
    const user = userEvent.setup();
    await user.click(screen.getByTestId('park-here-cta'));

    await waitFor(() => {
      expect(toast.showSuccess).toHaveBeenCalledWith('Parking location saved.');
    });
    expect(startCalls).toBe(1);
    expect(queryClient.getQueryData(parkingKeys.activeSession())).toEqual(createdSession);
  });

  it('shows Locating then Saving while work is in flight', async () => {
    let resolveGeo: ((pos: GeolocationPosition) => void) | undefined;
    stubGeolocation({
      getCurrentPosition: (success) => {
        resolveGeo = success;
      },
    });
    server.use(
      http.post(`${API_BASE}/parking/sessions`, async () => {
        await delay(200);
        return HttpResponse.json(createdSession, { status: 201 });
      }),
    );

    renderControl();
    const user = userEvent.setup();
    await user.click(screen.getByTestId('park-here-cta'));

    expect(await screen.findByText('Locating…')).toBeInTheDocument();
    expect(screen.getByTestId('park-here-cta')).toBeDisabled();
    expect(screen.getByTestId('park-here-cta')).toHaveAttribute('aria-busy', 'true');

    resolveGeo?.({ coords: { latitude: 38.42, longitude: 27.14 } } as GeolocationPosition);

    expect(await screen.findByText('Saving…')).toBeInTheDocument();
    await waitFor(() => {
      expect(toast.showSuccess).toHaveBeenCalled();
    });
  });

  it('toasts permission denied and does not call start', async () => {
    let startCalls = 0;
    stubGeolocation({
      getCurrentPosition: (_s, error) => {
        error?.({
          code: 1,
          PERMISSION_DENIED: 1,
          POSITION_UNAVAILABLE: 2,
          TIMEOUT: 3,
          message: 'denied',
        } as GeolocationPositionError);
      },
    });
    server.use(
      http.post(`${API_BASE}/parking/sessions`, () => {
        startCalls += 1;
        return HttpResponse.json(createdSession, { status: 201 });
      }),
    );

    renderControl();
    const user = userEvent.setup();
    await user.click(screen.getByTestId('park-here-cta'));

    await waitFor(() => {
      expect(toast.showError).toHaveBeenCalledWith(
        'Location permission is required to park here.',
      );
    });
    expect(startCalls).toBe(0);
  });

  it('toasts unsupported browser and does not call start', async () => {
    let startCalls = 0;
    stubGeolocation(undefined);
    server.use(
      http.post(`${API_BASE}/parking/sessions`, () => {
        startCalls += 1;
        return HttpResponse.json(createdSession, { status: 201 });
      }),
    );

    renderControl();
    const user = userEvent.setup();
    await user.click(screen.getByTestId('park-here-cta'));

    await waitFor(() => {
      expect(toast.showError).toHaveBeenCalledWith(
        'Location is not supported in this browser.',
      );
    });
    expect(startCalls).toBe(0);
  });

  it('rejects invalid coordinates without calling start', async () => {
    let startCalls = 0;
    stubGeolocation({
      getCurrentPosition: (success) =>
        success({ coords: { latitude: Number.NaN, longitude: 27.14 } } as GeolocationPosition),
    });
    server.use(
      http.post(`${API_BASE}/parking/sessions`, () => {
        startCalls += 1;
        return HttpResponse.json(createdSession, { status: 201 });
      }),
    );

    renderControl();
    const user = userEvent.setup();
    await user.click(screen.getByTestId('park-here-cta'));

    await waitFor(() => {
      expect(toast.showError).toHaveBeenCalledWith("Couldn't use that location. Try again.");
    });
    expect(startCalls).toBe(0);
  });

  it('prevents duplicate mutations while busy', async () => {
    let startCalls = 0;
    let resolveGeo: ((pos: GeolocationPosition) => void) | undefined;
    stubGeolocation({
      getCurrentPosition: (success) => {
        resolveGeo = success;
      },
    });
    server.use(
      http.post(`${API_BASE}/parking/sessions`, async () => {
        startCalls += 1;
        await delay(100);
        return HttpResponse.json(createdSession, { status: 201 });
      }),
    );

    renderControl();
    const user = userEvent.setup();
    const cta = screen.getByTestId('park-here-cta');
    await user.click(cta);
    await user.click(cta);
    await user.click(cta);

    resolveGeo?.({ coords: { latitude: 38.42, longitude: 27.14 } } as GeolocationPosition);

    await waitFor(() => {
      expect(toast.showSuccess).toHaveBeenCalled();
    });
    expect(startCalls).toBe(1);
  });

  it('reconciles ACTIVE on 409 and shows a friendly info toast', async () => {
    let activeGets = 0;
    server.use(
      http.post(`${API_BASE}/parking/sessions`, () =>
        HttpResponse.json(
          apiErrorBody('ACTIVE_PARKING_SESSION_EXISTS', 'Active session exists'),
          { status: 409 },
        ),
      ),
      http.get(`${API_BASE}/parking/sessions/active`, () => {
        activeGets += 1;
        return HttpResponse.json(createdSession);
      }),
    );

    const { queryClient } = renderControl();
    const user = userEvent.setup();
    await user.click(screen.getByTestId('park-here-cta'));

    await waitFor(() => {
      expect(toast.showInfo).toHaveBeenCalledWith(
        'You already have an active parking session.',
      );
    });
    expect(toast.showError).not.toHaveBeenCalled();
    expect(activeGets).toBeGreaterThanOrEqual(1);
    expect(queryClient.getQueryData(parkingKeys.activeSession())).toEqual(createdSession);
  });

  it('ignores geolocation that resolves after logout', async () => {
    let resolveGeo: ((pos: GeolocationPosition) => void) | undefined;
    stubGeolocation({
      getCurrentPosition: (success) => {
        resolveGeo = success;
      },
    });
    let startCalls = 0;
    server.use(
      http.post(`${API_BASE}/parking/sessions`, () => {
        startCalls += 1;
        return HttpResponse.json(createdSession, { status: 201 });
      }),
    );

    const { queryClient } = renderControl();
    const user = userEvent.setup();
    await user.click(screen.getByTestId('park-here-cta'));
    expect(screen.getByTestId('park-here-cta')).toBeDisabled();

    runtime.authStore.getState().clearSession();
    resolveGeo?.({ coords: { latitude: 38.42, longitude: 27.14 } } as GeolocationPosition);

    await waitFor(() => {
      expect(screen.getByTestId('park-here-cta')).not.toBeDisabled();
    });
    expect(startCalls).toBe(0);
    expect(queryClient.getQueryData(parkingKeys.activeSession())).toBeUndefined();
    expect(toast.showSuccess).not.toHaveBeenCalled();
  });
});
