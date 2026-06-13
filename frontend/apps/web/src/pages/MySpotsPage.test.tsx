import type { Spot } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { MySpotsPage } from './MySpotsPage';

const spot: Spot = {
  id: '0b8f6c3a-0000-0000-0000-0000000000s1',
  mediaId: '0b8f6c3a-0000-0000-0000-0000000000m1',
  latitude: 41.01,
  longitude: 28.97,
  addressText: '12 Curb Lane',
  description: 'Shaded street spot',
  manualLocationEdited: false,
  suitableVehicleTypes: ['SEDAN'],
  parkingContext: 'STREET_PARKING',
  legalStatus: 'LEGAL',
  violationReasons: [],
  status: 'ACTIVE',
  expiresAt: '2026-06-13T12:00:00Z',
  createdAt: '2026-06-13T10:00:00Z',
  updatedAt: '2026-06-13T10:00:00Z',
  ownerUserId: '0b8f6c3a-0000-0000-0000-0000000000u1',
  confidenceScore: 80,
  verificationCount: 2,
  filledReportCount: 0,
};

function useMySpotsHandlers(spots: Spot[]) {
  server.use(
    http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/parking/my-spots`, () => HttpResponse.json(spots)),
  );
}

describe('MySpotsPage', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['USER']);
  });

  it('shows the empty state when no spots have been shared', async () => {
    useMySpotsHandlers([]);
    renderWithProviders(<MySpotsPage />, { initialEntries: ['/my-spots'] });

    expect(await screen.findByText('No spots yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Share your first spot/ })).toHaveAttribute(
      'href',
      '/upload',
    );
  });

  it('renders a shared spot with status and owner signals', async () => {
    useMySpotsHandlers([spot]);
    renderWithProviders(<MySpotsPage />, { initialEntries: ['/my-spots'] });

    expect(await screen.findByRole('link', { name: '12 Curb Lane' })).toBeInTheDocument();
    expect(screen.getByText(/2 verifications/)).toBeInTheDocument();
    expect(screen.getByText(/Confidence 80/)).toBeInTheDocument();
  });
});
