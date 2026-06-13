import type { Spot } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { UploadPage } from './UploadPage';

// Leaflet needs real DOM sizing/canvas that jsdom lacks; the picker is not under test here.
vi.mock('@/components/map/MapPicker', () => ({
  MapPicker: () => null,
}));

const SPOT_ID = '0b8f6c3a-0000-0000-0000-000000000010';

const createdSpot: Spot = {
  id: SPOT_ID,
  mediaId: '0b8f6c3a-0000-0000-0000-000000000011',
  ownerUserId: '0b8f6c3a-0000-0000-0000-000000000012',
  latitude: 41.01,
  longitude: 29.02,
  addressText: null,
  description: null,
  manualLocationEdited: true,
  suitableVehicleTypes: ['SEDAN'],
  parkingContext: 'STREET_PARKING',
  legalStatus: 'LEGAL',
  violationReasons: [],
  status: 'ACTIVE',
  confidenceScore: 0,
  verificationCount: 0,
  filledReportCount: 0,
  expiresAt: '2026-06-11T12:00:00Z',
  createdAt: '2026-06-11T09:00:00Z',
  updatedAt: '2026-06-11T09:00:00Z',
};

function renderUpload() {
  return renderWithProviders(
    <Routes>
      <Route path="/upload" element={<UploadPage />} />
      <Route path="/spots/:spotId" element={<div>Spot detail</div>} />
    </Routes>,
    { initialEntries: ['/upload'] },
  );
}

function imageFile(name = 'spot.jpg', type = 'image/jpeg', size = 1024) {
  const file = new File(['x'], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

async function fillValidForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Latitude'), '41.01');
  await user.type(screen.getByLabelText('Longitude'), '29.02');
  await user.click(screen.getByText('Sedan'));
  await user.selectOptions(screen.getByLabelText('Parking context'), 'STREET_PARKING');
  await user.click(screen.getByText('Legal'));
}

describe('UploadPage', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['USER']);
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
  });

  it('rejects a file that exceeds the 10MB limit', async () => {
    renderUpload();
    const user = userEvent.setup();

    await user.upload(screen.getByLabelText('Spot photo'), imageFile('big.jpg', 'image/jpeg', 11 * 1024 * 1024));

    expect(await screen.findByText('Photo must be at most 10MB')).toBeInTheDocument();
  });

  it('rejects a non-image file type', async () => {
    renderUpload();
    // applyAccept: false bypasses the input's accept filter so the Zod type check runs.
    const user = userEvent.setup({ applyAccept: false });

    await user.upload(screen.getByLabelText('Spot photo'), imageFile('doc.pdf', 'application/pdf'));

    expect(
      await screen.findByText('Only JPEG, PNG and WebP images are allowed'),
    ).toBeInTheDocument();
  });

  it('reuses the uploaded media when create fails, then succeeds without re-uploading', async () => {
    let uploadCalls = 0;
    let createCalls = 0;
    server.use(
      http.post(`${API_BASE}/media/upload`, () => {
        uploadCalls += 1;
        return HttpResponse.json({
          mediaId: createdSpot.mediaId,
          status: 'READY',
          contentType: 'image/jpeg',
          fileSize: 1024,
        });
      }),
      http.post(`${API_BASE}/parking/spots`, () => {
        createCalls += 1;
        if (createCalls === 1) {
          return HttpResponse.json(apiErrorBody('VALIDATION_ERROR', 'Create failed'), {
            status: 422,
          });
        }
        return HttpResponse.json(createdSpot);
      }),
    );

    renderUpload();
    const user = userEvent.setup();

    await user.upload(screen.getByLabelText('Spot photo'), imageFile());
    await fillValidForm(user);

    // First attempt: upload succeeds, create fails — media is kept for retry.
    await user.click(screen.getByRole('button', { name: 'Upload & create spot' }));
    expect(
      await screen.findByText(/Your photo was uploaded successfully/),
    ).toBeInTheDocument();

    // Second attempt: create succeeds, photo is reused (no second upload).
    await user.click(screen.getByRole('button', { name: 'Upload & create spot' }));
    expect(await screen.findByRole('heading', { name: 'Spot created' })).toBeInTheDocument();

    expect(uploadCalls).toBe(1);
    expect(createCalls).toBe(2);
  });

  it('requires a photo before submitting', async () => {
    renderUpload();
    const user = userEvent.setup();

    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: 'Upload & create spot' }));

    expect(await screen.findByText('Choose a photo to upload')).toBeInTheDocument();
  });
});
