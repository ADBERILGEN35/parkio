import type { AnalyticsOverview } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { AnalyticsPage } from './AnalyticsPage';

const overview: AnalyticsOverview = {
  totalParkingCreated: 12,
  totalParkingVerified: 5,
  totalParkingClaimed: 3,
  totalParkingRejected: 1,
  totalPointsEarned: 200,
  totalLevelUps: 4,
  totalNotificationsCreated: 8,
};

function useAnalyticsHandlers() {
  server.use(
    http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/analytics/overview`, () => HttpResponse.json(overview)),
    http.get(`${API_BASE}/analytics/daily`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/analytics/parking`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/analytics/metrics`, () => HttpResponse.json([])),
  );
}

describe('AnalyticsPage', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['ADMIN']);
  });

  it('renders the overview KPI cards', async () => {
    useAnalyticsHandlers();
    renderWithProviders(<AnalyticsPage />, { initialEntries: ['/analytics'] });

    expect(await screen.findByText('Spots created')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
  });

  it('shows the empty state for daily snapshots', async () => {
    useAnalyticsHandlers();
    renderWithProviders(<AnalyticsPage />, { initialEntries: ['/analytics'] });

    expect(await screen.findByText('No daily snapshots yet')).toBeInTheDocument();
  });

  it('shows the friendly 403 message when looking up another user', async () => {
    useAnalyticsHandlers();
    server.use(
      http.get(`${API_BASE}/analytics/users/:userId`, () =>
        HttpResponse.json(apiErrorBody('FORBIDDEN', 'Forbidden'), { status: 403 }),
      ),
    );
    renderWithProviders(<AnalyticsPage />, { initialEntries: ['/analytics'] });
    const user = userEvent.setup();

    await user.type(
      await screen.findByLabelText('User id'),
      '11111111-1111-1111-1111-111111111111',
    );
    await user.click(screen.getByRole('button', { name: 'Fetch analytics' }));

    expect(
      await screen.findByText(/You may only view your own analytics/),
    ).toBeInTheDocument();
  });
});
