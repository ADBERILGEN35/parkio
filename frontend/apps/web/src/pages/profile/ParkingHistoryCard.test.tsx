import type { ParkingSessionResponse } from '@parkio/types';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import type { WebAppRuntime } from '@/app/runtime';
import * as openMaps from '@/components/parking/openParkingMaps';
import * as shareLoc from '@/components/parking/shareParkingLocation';
import { parkingKeys } from '@/data/keys';
import * as toast from '@/lib/toast';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import {
  createTestAppRuntime,
  renderWithProviders as renderWithBaseProviders,
  signInAs,
} from '@/test/utils';
import { ParkingHistoryCard } from './ParkingHistoryCard';

let runtime: WebAppRuntime;

function session(
  overrides: Partial<ParkingSessionResponse> & Pick<ParkingSessionResponse, 'id' | 'status'>,
): ParkingSessionResponse {
  return {
    parkingSource: 'MANUAL',
    startedAt: '2026-07-25T10:00:00.000Z',
    endedAt: '2026-07-25T11:30:00.000Z',
    latitude: 38.42,
    longitude: 27.14,
    estimatedFee: null,
    ...overrides,
  };
}

const completed = session({
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  status: 'COMPLETED',
  parkingSource: 'MANUAL',
});

const cancelled = session({
  id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  status: 'CANCELLED',
  parkingSource: 'COMMUNITY',
  startedAt: '2026-07-24T09:00:00.000Z',
  endedAt: '2026-07-24T09:45:00.000Z',
});

function historyUrl() {
  return `${API_BASE}/parking/sessions/history`;
}

function renderHistory(initialEntries = ['/profile']) {
  return renderWithBaseProviders(
    <Routes>
      <Route path="/profile" element={<ParkingHistoryCard />} />
      <Route path="/map" element={<div>Map page</div>} />
    </Routes>,
    { runtime, initialEntries },
  );
}

beforeEach(() => {
  runtime = createTestAppRuntime();
  signInAs(runtime, ['USER']);
  vi.spyOn(toast, 'showSuccess').mockImplementation(() => undefined);
  vi.spyOn(toast, 'showError').mockImplementation(() => undefined);
  vi.spyOn(toast, 'showInfo').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('ParkingHistoryCard', () => {
  it('renders empty state and links to the map', async () => {
    server.use(
      http.get(historyUrl(), () => HttpResponse.json({ items: [], nextCursor: null })),
    );
    renderHistory();

    expect(await screen.findByText('No parking history yet')).toBeInTheDocument();
    expect(screen.queryByTestId('parking-history-delete-all')).not.toBeInTheDocument();
    expect(screen.getByTestId('parking-history-go-map')).toHaveAttribute('href', '/map');
  });

  it('shows initial error with retry that recovers', async () => {
    let attempts = 0;
    server.use(
      http.get(historyUrl(), () => {
        attempts += 1;
        if (attempts === 1) {
          return HttpResponse.json(apiErrorBody('INTERNAL_ERROR', 'boom'), { status: 500 });
        }
        return HttpResponse.json({ items: [completed], nextCursor: null });
      }),
    );
    renderHistory();
    const user = userEvent.setup();

    expect(await screen.findByTestId('parking-history-error')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Try again/i }));
    expect(await screen.findByTestId('parking-history-list')).toBeInTheDocument();
    expect(screen.getByText('Completed')).toBeInTheDocument();
  });

  it('renders completed and cancelled rows with sources and no ids/coords', async () => {
    server.use(
      http.get(historyUrl(), () =>
        HttpResponse.json({ items: [completed, cancelled], nextCursor: null }),
      ),
    );
    renderHistory();

    const list = await screen.findByTestId('parking-history-list');
    expect(within(list).getByText('Completed')).toBeInTheDocument();
    expect(within(list).getByText('Cancelled')).toBeInTheDocument();
    expect(within(list).getByText('Saved manually')).toBeInTheDocument();
    expect(within(list).getByText('Community parking spot')).toBeInTheDocument();
    expect(screen.queryByText(completed.id)).not.toBeInTheDocument();
    expect(screen.queryByText(/38\.42/)).not.toBeInTheDocument();
    expect(screen.queryByText(/27\.14/)).not.toBeInTheDocument();
  });

  it('renders facility and auto sources without crashing', async () => {
    server.use(
      http.get(historyUrl(), () =>
        HttpResponse.json({
          items: [
            { ...completed, parkingSource: 'FACILITY' },
            {
              ...cancelled,
              parkingSource: 'AUTO',
            },
          ],
          nextCursor: null,
        }),
      ),
    );
    renderHistory();
    expect(await screen.findByText('Facility parking')).toBeInTheDocument();
    expect(screen.getByText('Automatically saved')).toBeInTheDocument();
  });

  it('hides ACTIVE payloads from the history list', async () => {
    const active = session({
      id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      status: 'ACTIVE',
      endedAt: null,
    });
    server.use(
      http.get(historyUrl(), () =>
        HttpResponse.json({ items: [active, completed], nextCursor: null }),
      ),
    );
    renderHistory();
    await screen.findByTestId('parking-history-list');
    expect(screen.getAllByTestId('parking-history-row')).toHaveLength(1);
    expect(screen.getByText('Completed')).toBeInTheDocument();
    expect(screen.queryByText('Active')).not.toBeInTheDocument();
  });

  it('appends next page without duplicating rows and blocks double load-more', async () => {
    let page2Hits = 0;
    server.use(
      http.get(historyUrl(), ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        if (!cursor) {
          return HttpResponse.json({ items: [completed], nextCursor: 'cursor-2' });
        }
        page2Hits += 1;
        return HttpResponse.json({ items: [cancelled, completed], nextCursor: null });
      }),
    );
    renderHistory();
    const user = userEvent.setup();

    await screen.findByText('Completed');
    const loadMore = await screen.findByTestId('parking-history-load-more');
    await user.dblClick(loadMore);

    await waitFor(() => expect(screen.getByText('Cancelled')).toBeInTheDocument());
    expect(screen.getAllByTestId('parking-history-row')).toHaveLength(2);
    expect(page2Hits).toBe(1);
  });

  it('preserves loaded rows when the next page fails', async () => {
    server.use(
      http.get(historyUrl(), ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        if (!cursor) {
          return HttpResponse.json({ items: [completed], nextCursor: 'cursor-2' });
        }
        return HttpResponse.json(apiErrorBody('INTERNAL_ERROR', 'page2'), { status: 500 });
      }),
    );
    renderHistory();
    const user = userEvent.setup();

    await screen.findByText('Completed');
    await user.click(screen.getByTestId('parking-history-load-more'));
    expect(await screen.findByText(/Couldn't load more history/i)).toBeInTheDocument();
    expect(screen.getByText('Completed')).toBeInTheDocument();
    expect(screen.getByTestId('parking-history-list')).toBeInTheDocument();
  });

  it('opens maps and shares through PR4 helpers', async () => {
    const mapsSpy = vi.spyOn(openMaps, 'openParkingLocationInMaps').mockReturnValue(true);
    const shareSpy = vi.spyOn(shareLoc, 'shareParkingLocation').mockResolvedValue({
      ok: true,
      method: 'share',
    });
    server.use(
      http.get(historyUrl(), () =>
        HttpResponse.json({ items: [completed], nextCursor: null }),
      ),
    );
    renderHistory();
    const user = userEvent.setup();

    await screen.findByText('Completed');
    await user.click(screen.getByTestId('parking-history-open-maps'));
    expect(mapsSpy).toHaveBeenCalledWith(38.42, 27.14);

    await user.click(screen.getByTestId('parking-history-share'));
    expect(shareSpy).toHaveBeenCalled();
    const shareArg = shareSpy.mock.calls[0]![0];
    expect(JSON.stringify(shareArg)).not.toContain(completed.id);
  });

  it('disables open/share for invalid coordinates', async () => {
    const coords = await import('@/components/map/parkedCarCoords');
    vi.spyOn(coords, 'isUsableParkedCoordinate').mockReturnValue(false);
    server.use(
      http.get(historyUrl(), () =>
        HttpResponse.json({ items: [completed], nextCursor: null }),
      ),
    );
    renderHistory();
    await screen.findByText('Completed');
    expect(screen.getByTestId('parking-history-open-maps')).toBeDisabled();
    expect(screen.getByTestId('parking-history-share')).toBeDisabled();
  });

  it('requires confirmation for single delete and can keep the record', async () => {
    const deleteSpy = vi.fn();
    server.use(
      http.get(historyUrl(), () =>
        HttpResponse.json({ items: [completed], nextCursor: null }),
      ),
      http.delete(`${API_BASE}/parking/sessions/${completed.id}`, () => {
        deleteSpy();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderHistory();
    const user = userEvent.setup();

    await screen.findByText('Completed');
    await user.click(screen.getByTestId('parking-history-delete'));
    expect(screen.getByTestId('parking-history-delete-confirm')).toBeInTheDocument();
    await user.click(screen.getByTestId('parking-history-keep-record'));
    expect(screen.queryByTestId('parking-history-delete-confirm')).not.toBeInTheDocument();
    expect(deleteSpy).not.toHaveBeenCalled();
  });

  it('deletes one row after confirm and reconciles the list', async () => {
    let deleted = false;
    server.use(
      http.get(historyUrl(), () =>
        HttpResponse.json({
          items: deleted ? [cancelled] : [completed, cancelled],
          nextCursor: null,
        }),
      ),
      http.delete(`${API_BASE}/parking/sessions/${completed.id}`, async () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderHistory();
    const user = userEvent.setup();

    await screen.findByText('Completed');
    const completedRow = screen.getByText('Completed').closest('[data-testid="parking-history-row"]')!;
    await user.click(within(completedRow).getByTestId('parking-history-delete'));
    await user.click(screen.getByTestId('parking-history-confirm-delete'));

    await waitFor(() => expect(toast.showSuccess).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByText('Completed')).not.toBeInTheDocument());
    expect(screen.getByText('Cancelled')).toBeInTheDocument();
    await waitFor(() => {
      const focused = document.activeElement as HTMLElement | null;
      expect(focused?.getAttribute('data-history-session-id')).toBe(cancelled.id);
    });
  });

  it('preserves the row when single delete fails', async () => {
    server.use(
      http.get(historyUrl(), () =>
        HttpResponse.json({ items: [completed], nextCursor: null }),
      ),
      http.delete(`${API_BASE}/parking/sessions/${completed.id}`, () =>
        HttpResponse.json(apiErrorBody('INTERNAL_ERROR', 'nope'), { status: 500 }),
      ),
    );
    renderHistory();
    const user = userEvent.setup();

    await screen.findByText('Completed');
    await user.click(screen.getByTestId('parking-history-delete'));
    await user.click(screen.getByTestId('parking-history-confirm-delete'));

    await waitFor(() => expect(toast.showError).toHaveBeenCalled());
    expect(screen.getByText('Completed')).toBeInTheDocument();
  });

  it('requires confirmation for delete-all and does not clear active-session cache', async () => {
    const active = session({
      id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      status: 'ACTIVE',
      endedAt: null,
    });
    let wiped = false;
    server.use(
      http.get(historyUrl(), () =>
        HttpResponse.json({
          items: wiped ? [] : [completed],
          nextCursor: null,
        }),
      ),
      http.delete(`${API_BASE}/parking/sessions/history`, async () => {
        wiped = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const view = renderHistory();
    view.queryClient.setQueryData(parkingKeys.activeSession(), active);

    const user = userEvent.setup();
    await screen.findByText('Completed');
    await user.click(screen.getByTestId('parking-history-delete-all'));
    expect(screen.getByTestId('parking-history-delete-all-confirm')).toBeInTheDocument();
    await user.click(screen.getByTestId('parking-history-confirm-delete-all'));

    await waitFor(() =>
      expect(screen.getByText('No parking history yet')).toBeInTheDocument(),
    );
    expect(view.queryClient.getQueryData(parkingKeys.activeSession())).toEqual(active);
    await waitFor(() => {
      expect(document.activeElement).toBe(
        screen.getByTestId('parking-history-empty-focus'),
      );
    });
  });

  it('keeps the list when delete-all fails', async () => {
    server.use(
      http.get(historyUrl(), () =>
        HttpResponse.json({ items: [completed], nextCursor: null }),
      ),
      http.delete(`${API_BASE}/parking/sessions/history`, () =>
        HttpResponse.json(apiErrorBody('INTERNAL_ERROR', 'nope'), { status: 500 }),
      ),
    );
    renderHistory();
    const user = userEvent.setup();

    await screen.findByText('Completed');
    await user.click(screen.getByTestId('parking-history-delete-all'));
    await user.click(screen.getByTestId('parking-history-confirm-delete-all'));
    await waitFor(() => expect(toast.showError).toHaveBeenCalled());
    expect(screen.getByText('Completed')).toBeInTheDocument();
  });
});