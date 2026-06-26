import type { PublicSpot } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { SpotDetailPage } from './SpotDetailPage';

// Leaflet needs real DOM sizing/canvas that jsdom lacks; the map is not under test here.
vi.mock('@/components/map/SpotMap', () => ({
  SpotMap: () => null,
}));

const SPOT_ID = '0b8f6c3a-0000-0000-0000-000000000001';

const spot: PublicSpot = {
  id: SPOT_ID,
  mediaId: '0b8f6c3a-0000-0000-0000-000000000002',
  latitude: 41.01,
  longitude: 29.02,
  addressText: 'Test Street 1',
  description: 'Near the corner',
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

function useSpotHandlers() {
  server.use(
    http.get(`${API_BASE}/parking/spots/${SPOT_ID}`, () => HttpResponse.json(spot)),
    http.get(`${API_BASE}/parking/spots/${SPOT_ID}/media-access-url`, () =>
      HttpResponse.json({
        spotId: SPOT_ID,
        mediaId: spot.mediaId,
        accessUrl: 'https://signed.example/photo.jpg',
        expiresAt: '2026-06-11T10:05:00Z',
      }),
    ),
  );
}

function renderSpotDetail() {
  return renderWithProviders(
    <Routes>
      <Route path="/spots/:spotId" element={<SpotDetailPage />} />
    </Routes>,
    { initialEntries: [`/spots/${SPOT_ID}`] },
  );
}

describe('SpotDetailPage', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['USER']);
  });

  it('shows a friendly message when the spot is not found', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/${SPOT_ID}`, () =>
        HttpResponse.json(apiErrorBody('SPOT_NOT_FOUND', 'Spot not found'), { status: 404 }),
      ),
    );

    renderSpotDetail();

    expect(
      await screen.findByText(
        'This spot was not found — it may have expired, been filled, or been removed.',
      ),
    ).toBeInTheDocument();
  });

  it('shows the friendly duplicate message on 409 ALREADY_VERIFIED', async () => {
    useSpotHandlers();
    server.use(
      http.post(`${API_BASE}/parking/spots/${SPOT_ID}/verify`, () =>
        HttpResponse.json(apiErrorBody('ALREADY_VERIFIED', 'Already verified'), { status: 409 }),
      ),
    );

    renderSpotDetail();
    const user = userEvent.setup();

    await user.selectOptions(
      await screen.findByLabelText(/Verify — what did you observe/),
      'AVAILABLE',
    );
    await user.click(screen.getByRole('button', { name: 'Submit verification' }));

    expect(await screen.findByText('You have already verified this spot.')).toBeInTheDocument();
    expect(screen.getByText('Code: ALREADY_VERIFIED')).toBeInTheDocument();
  });

  it('requires confirmation before an irreversible claim, then shows success', async () => {
    useSpotHandlers();
    server.use(
      http.post(`${API_BASE}/parking/spots/${SPOT_ID}/claim`, () =>
        HttpResponse.json({ ...spot, status: 'FILLED' }),
      ),
    );

    renderSpotDetail();
    const user = userEvent.setup();

    // First tap only reveals the confirmation — no request fires yet.
    await user.click(await screen.findByRole('button', { name: 'Claim this spot' }));
    expect(await screen.findByText(/can't be undone/i)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Yes, mark as filled' }));
    expect(
      await screen.findByText('Spot claimed — it is now marked as filled.'),
    ).toBeInTheDocument();
  });

  it('lets the user cancel an unintended claim without firing a request', async () => {
    useSpotHandlers();
    let claimCalls = 0;
    server.use(
      http.post(`${API_BASE}/parking/spots/${SPOT_ID}/claim`, () => {
        claimCalls += 1;
        return HttpResponse.json({ ...spot, status: 'FILLED' });
      }),
    );

    renderSpotDetail();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Claim this spot' }));
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));

    // Back to the initial affordance, and the claim endpoint was never called.
    expect(screen.getByRole('button', { name: 'Claim this spot' })).toBeInTheDocument();
    expect(claimCalls).toBe(0);
  });

  it('keeps spot details visible when the photo is unavailable', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/${SPOT_ID}`, () => HttpResponse.json(spot)),
      http.get(`${API_BASE}/parking/spots/${SPOT_ID}/media-access-url`, () =>
        HttpResponse.json(apiErrorBody('MEDIA_ACCESS_UNAVAILABLE', 'Media unavailable'), {
          status: 503,
        }),
      ),
    );

    renderSpotDetail();

    expect(await screen.findByText('The photo is temporarily unavailable.')).toBeInTheDocument();
    // The spot details (summary header) must still render alongside the photo error.
    expect(screen.getByRole('heading', { name: 'Test Street 1' })).toBeInTheDocument();
  });

  it('re-requests the signed URL when "Refresh photo URL" is clicked', async () => {
    let mediaCalls = 0;
    server.use(
      http.get(`${API_BASE}/parking/spots/${SPOT_ID}`, () => HttpResponse.json(spot)),
      http.get(`${API_BASE}/parking/spots/${SPOT_ID}/media-access-url`, () => {
        mediaCalls += 1;
        return HttpResponse.json({
          spotId: SPOT_ID,
          mediaId: spot.mediaId,
          accessUrl: 'https://signed.example/photo.jpg',
          expiresAt: '2026-06-11T10:05:00Z',
        });
      }),
    );

    renderSpotDetail();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Refresh photo URL' }));

    await waitFor(() => expect(mediaCalls).toBe(2));
  });
});
