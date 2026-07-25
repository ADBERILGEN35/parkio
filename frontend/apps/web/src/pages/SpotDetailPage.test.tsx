import type { PublicSpot } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { renderWithProviders } from '@/test/utils';
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
    { authRoles: ['USER'], initialEntries: [`/spots/${SPOT_ID}`] },
  );
}

describe('SpotDetailPage', () => {
  it('shows a friendly message when the spot is not found', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/${SPOT_ID}`, () =>
        HttpResponse.json(apiErrorBody('SPOT_NOT_FOUND', 'Spot not found'), { status: 404 }),
      ),
    );

    renderSpotDetail();

    expect(
      await screen.findByRole('heading', { name: 'Spot not found' }),
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
    let activeFetches = 0;
    server.use(
      http.post(`${API_BASE}/parking/spots/${SPOT_ID}/claim`, () =>
        HttpResponse.json({ ...spot, status: 'FILLED' }),
      ),
      http.get(`${API_BASE}/parking/sessions/active`, () => {
        activeFetches += 1;
        return HttpResponse.json({
          id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
          status: 'ACTIVE',
          parkingSource: 'COMMUNITY',
          startedAt: '2026-06-11T09:30:00Z',
          endedAt: null,
          latitude: spot.latitude,
          longitude: spot.longitude,
          estimatedFee: null,
        });
      }),
    );

    renderSpotDetail();
    const user = userEvent.setup();

    // First tap only reveals the confirmation — no request fires yet.
    await user.click(await screen.findByRole('button', { name: 'I parked here' }));
    expect(await screen.findByRole('button', { name: 'Yes, I parked here' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Yes, I parked here' }));
    expect(
      await screen.findByText('Parked — your session is active and the spot is marked filled.'),
    ).toBeInTheDocument();
    await waitFor(() => expect(activeFetches).toBeGreaterThanOrEqual(1));
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

    await user.click(await screen.findByRole('button', { name: 'I parked here' }));
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));

    // Back to the initial affordance, and the claim endpoint was never called.
    expect(screen.getByRole('button', { name: 'I parked here' })).toBeInTheDocument();
    expect(claimCalls).toBe(0);
  });

  it('preserves an existing ACTIVE session when claim returns ACTIVE_PARKING_SESSION_EXISTS', async () => {
    useSpotHandlers();
    let activeFetches = 0;
    server.use(
      http.post(`${API_BASE}/parking/spots/${SPOT_ID}/claim`, () =>
        HttpResponse.json(apiErrorBody('ACTIVE_PARKING_SESSION_EXISTS', 'Active session exists'), {
          status: 409,
        }),
      ),
      http.get(`${API_BASE}/parking/sessions/active`, () => {
        activeFetches += 1;
        return HttpResponse.json({
          id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
          status: 'ACTIVE',
          parkingSource: 'MANUAL',
          startedAt: '2026-06-11T08:00:00Z',
          endedAt: null,
          latitude: 40.0,
          longitude: 29.0,
          estimatedFee: null,
        });
      }),
    );

    renderSpotDetail();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'I parked here' }));
    await user.click(screen.getByRole('button', { name: 'Yes, I parked here' }));

    expect(
      await screen.findByText(
        'You already have an active parking session. Leave or cancel it before parking here.',
      ),
    ).toBeInTheDocument();
    await waitFor(() => expect(activeFetches).toBeGreaterThanOrEqual(1));
    // Spot must not flip to claimed UI when personal claim was rejected.
    expect(screen.queryByText(/your session is active and the spot is marked filled/i)).not.toBeInTheDocument();
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
