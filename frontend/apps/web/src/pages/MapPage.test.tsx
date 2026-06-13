import type { PublicSpot } from '@parkio/types';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { MapPage } from './MapPage';

// Leaflet can't render in jsdom; stub the map and expose a button that simulates
// clicking it to set the search center, so coordinate state can be asserted.
vi.mock('@/components/map/NearbySpotsMap', () => ({
  NearbySpotsMap: ({ onPickCenter }: { onPickCenter: (lat: number, lng: number) => void }) => (
    <button type="button" onClick={() => onPickCenter(41.5, 29.5)}>
      stub-pick-center
    </button>
  ),
}));

const spot: PublicSpot = {
  id: '0b8f6c3a-0000-0000-0000-000000000010',
  mediaId: '0b8f6c3a-0000-0000-0000-000000000011',
  latitude: 41.51,
  longitude: 29.51,
  addressText: 'Stub Address 7',
  description: null,
  manualLocationEdited: false,
  suitableVehicleTypes: ['SEDAN'],
  parkingContext: 'STREET_PARKING',
  legalStatus: 'LEGAL',
  violationReasons: [],
  status: 'ACTIVE',
  expiresAt: '2026-06-11T12:00:00Z',
  createdAt: '2026-06-11T09:00:00Z',
  updatedAt: '2026-06-11T09:00:00Z',
};

describe('MapPage', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['USER']);
    // Shell unread badge fetches notifications on mount when AppShell is rendered.
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
  });

  it('fills the coordinate fields when a location is picked on the map', async () => {
    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'stub-pick-center' }));

    expect(screen.getByLabelText('Latitude')).toHaveValue('41.5');
    expect(screen.getByLabelText('Longitude')).toHaveValue('29.5');
  });

  it('searches nearby spots using the picked center', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'stub-pick-center' }));
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));

    const cardLink = await screen.findByRole('link', { name: 'Stub Address 7' });
    expect(cardLink).toBeInTheDocument();
    expect(cardLink).toHaveAttribute('href', `/spots/${spot.id}`);
  });

  it('shows an empty state when no spots are found', async () => {
    server.use(http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([])));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'stub-pick-center' }));
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));

    expect(await screen.findByText('No spots nearby')).toBeInTheDocument();
  });
});
