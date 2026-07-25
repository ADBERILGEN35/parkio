import { PARKING_STATUSES, type ParkingStatus, type PublicSpot, type Spot } from '@parkio/types';
import { getSpotStatusVisual } from '@parkio/ui';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders } from '@/test/utils';
import { MySpotsPage } from './MySpotsPage';
import { SpotDetailPage } from './SpotDetailPage';

// Leaflet needs real DOM sizing/canvas that jsdom lacks; the map is not under test here.
vi.mock('@/components/map/SpotMap', () => ({
  SpotMap: () => null,
}));

const SPOT_ID = '0b8f6c3a-1111-0000-0000-000000000001';
const MEDIA_ID = '0b8f6c3a-1111-0000-0000-000000000002';
const OWNER_ID = '0b8f6c3a-1111-0000-0000-000000000003';

/**
 * A spot still awaiting a verdict. `expiresAt` is null while pending — the UI must never
 * invent a countdown from a missing lifetime.
 */
function pendingSpot(status: ParkingStatus): PublicSpot {
  return {
    id: SPOT_ID,
    mediaId: MEDIA_ID,
    latitude: 41.01,
    longitude: 29.02,
    addressText: 'Review Street 1',
    description: null,
    manualLocationEdited: false,
    suitableVehicleTypes: ['SEDAN'],
    parkingContext: 'STREET_PARKING',
    legalStatus: 'LEGAL',
    violationReasons: [],
    status,
    expiresAt: null,
    createdAt: '2026-06-11T09:00:00Z',
    updatedAt: '2026-06-11T09:00:00Z',
  };
}

function ownedSpot(status: ParkingStatus): Spot {
  return {
    ...pendingSpot(status),
    ownerUserId: OWNER_ID,
    confidenceScore: 1,
    verificationCount: 0,
    filledReportCount: 0,
  };
}

function renderDetail() {
  return renderWithProviders(
    <Routes>
      <Route path="/spots/:spotId" element={<SpotDetailPage />} />
    </Routes>,
    { authRoles: ['USER'], initialEntries: [`/spots/${SPOT_ID}`] },
  );
}

describe('spot lifecycle status presentation', () => {
  it('gives every lifecycle state its own distinguishable badge', () => {
    const labels = PARKING_STATUSES.map((status) => getSpotStatusVisual(status).label);
    const icons = PARKING_STATUSES.map((status) => getSpotStatusVisual(status).icon);

    // A user must always be able to tell pending, approved, rejected, review-failed and
    // expired apart — no lifecycle state may share another's label.
    expect(new Set(labels).size).toBe(PARKING_STATUSES.length);
    expect(labels).toContain('Under review');
    expect(labels).toContain('Review failed');
    expect(labels).toContain('Expired');
    expect(labels).toContain('Rejected');
    expect(icons.every((icon) => icon.length > 0)).toBe(true);
  });

  it('never renders an unknown badge for a status the backend can return', () => {
    for (const status of PARKING_STATUSES) {
      expect(getSpotStatusVisual(status).label).not.toBe('Unknown');
    }
  });
});

describe('SpotDetailPage lifecycle states', () => {
  it('shows a review-failed spot as failed rather than pending forever', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/${SPOT_ID}`, () =>
        HttpResponse.json(pendingSpot('REVIEW_FAILED')),
      ),
      http.get(`${API_BASE}/parking/spots/${SPOT_ID}/media-access-url`, () =>
        HttpResponse.json({
          spotId: SPOT_ID,
          mediaId: MEDIA_ID,
          accessUrl: 'https://signed.example/photo.jpg',
          expiresAt: '2026-06-11T10:05:00Z',
        }),
      ),
    );

    renderDetail();

    expect(await screen.findByText('Review failed')).toBeInTheDocument();
    expect(screen.queryByText('Under review')).not.toBeInTheDocument();
  });

  it('does not present a countdown while under review when expiresAt is null', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/${SPOT_ID}`, () =>
        HttpResponse.json(pendingSpot('PENDING_REVIEW')),
      ),
      http.get(`${API_BASE}/parking/spots/${SPOT_ID}/media-access-url`, () =>
        HttpResponse.json({
          spotId: SPOT_ID,
          mediaId: MEDIA_ID,
          accessUrl: 'https://signed.example/photo.jpg',
          expiresAt: '2026-06-11T10:05:00Z',
        }),
      ),
    );

    renderDetail();

    expect(await screen.findByText('Under review')).toBeInTheDocument();
    // The lifetime has not started, so the expiry tile says so instead of counting down.
    expect(await screen.findAllByText('Starts after review')).not.toHaveLength(0);
  });
});

describe('MySpotsPage lifecycle consistency', () => {
  it('labels a spot the same way the detail page does', async () => {
    server.use(
      http.get(`${API_BASE}/parking/my-spots`, () =>
        HttpResponse.json([ownedSpot('REVIEW_FAILED')]),
      ),
    );

    renderWithProviders(<MySpotsPage />, { authRoles: ['USER'] });

    // Same label the detail page renders for the same status — list and detail can never
    // disagree because both read the single status → visual map.
    expect(await screen.findByText('Review failed')).toBeInTheDocument();
  });
});
