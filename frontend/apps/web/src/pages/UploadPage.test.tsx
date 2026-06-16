import type { Spot } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mediaApi, parkingApi } from '@/api';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { UploadPage } from './UploadPage';

vi.mock('@/api', () => ({
  mediaApi: {
    uploadMedia: vi.fn(),
  },
  parkingApi: {
    createParkingSpot: vi.fn(),
  },
}));

// Leaflet can't render in jsdom; stub the picker with a button that simulates a
// map click setting the location.
vi.mock('@/components/map/MapPicker', () => ({
  MapPicker: ({ onPick }: { onPick: (lat: number, lng: number) => void }) => (
    <button type="button" onClick={() => onPick(41.5, 29.5)}>
      stub-pick-location
    </button>
  ),
}));

/** Default Nominatim base URL (no VITE override in the test env). */
const NOMINATIM_URL = 'https://nominatim.openstreetmap.org/search';

function nominatimItem(overrides: Record<string, unknown> = {}) {
  return {
    place_id: 11,
    name: 'Vali Nevzat Ayaz Lisesi',
    display_name: 'Vali Nevzat Ayaz Lisesi, Karşıyaka, İzmir',
    lat: '38.46',
    lon: '27.10',
    address: { city: 'İzmir', city_district: 'Karşıyaka' },
    ...overrides,
  };
}

/** Two "vali" matches used to exercise the typeahead dropdown. */
const valiItems = [
  nominatimItem(),
  nominatimItem({
    place_id: 12,
    name: 'Vali Konağı Caddesi',
    display_name: 'Vali Konağı Caddesi, Konak, İzmir',
    lat: '38.41',
    lon: '27.13',
    address: { city: 'İzmir', city_district: 'Konak' },
  }),
];

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

type User = ReturnType<typeof userEvent.setup>;

const clickNext = (user: User) => user.click(screen.getByRole('button', { name: 'Next' }));
const clickBack = (user: User) => user.click(screen.getByRole('button', { name: 'Back' }));

/** Step 1 → Step 2: choose a valid photo and advance to Location. */
async function completePhotoStep(user: User, file = imageFile()) {
  await user.upload(screen.getByLabelText('Spot photo'), file);
  await clickNext(user);
}

/** Step 2 → Step 3: fill coordinates (via the advanced disclosure) and advance. */
async function completeLocationStep(user: User) {
  // Latitude/Longitude live behind the "Advanced coordinates" disclosure.
  await user.click(screen.getByText('Advanced coordinates'));
  await user.type(screen.getByLabelText('Latitude'), '41.01');
  await user.type(screen.getByLabelText('Longitude'), '29.02');
  await clickNext(user);
}

/** Step 3 → Step 4: choose vehicle/context/legal and advance to Review. */
async function completeDetailsStep(user: User) {
  await user.click(screen.getByText('Sedan'));
  await user.selectOptions(screen.getByLabelText('Parking context'), 'STREET_PARKING');
  await user.click(screen.getByText('Legal'));
  await clickNext(user);
}

/** Drives Photo → Location → Details → Review, stopping on the Review step. */
async function advanceToReview(user: User) {
  await completePhotoStep(user);
  await completeLocationStep(user);
  await completeDetailsStep(user);
}

describe('UploadPage', () => {
  beforeEach(() => {
    vi.mocked(mediaApi.uploadMedia).mockReset();
    vi.mocked(parkingApi.createParkingSpot).mockReset();
    resetAuth();
    signInAs(['USER']);
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
  });

  it('rejects a file that exceeds the 10MB limit', async () => {
    renderUpload();
    const user = userEvent.setup();

    await user.upload(
      screen.getByLabelText('Spot photo'),
      imageFile('big.jpg', 'image/jpeg', 11 * 1024 * 1024),
    );

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

  it('requires a photo before advancing past the first step', async () => {
    renderUpload();
    const user = userEvent.setup();

    await clickNext(user);

    expect(await screen.findByText('Choose a photo to upload')).toBeInTheDocument();
    // Still on the Photo step — navigation was blocked.
    expect(screen.getByRole('heading', { name: '1. Photo' })).toBeInTheDocument();
  });

  it('moves forward and backward through the wizard steps', async () => {
    renderUpload();
    const user = userEvent.setup();

    expect(screen.getByRole('heading', { name: '1. Photo' })).toBeInTheDocument();

    await completePhotoStep(user);
    expect(screen.getByRole('heading', { name: '2. Location' })).toBeInTheDocument();

    await completeLocationStep(user);
    expect(screen.getByRole('heading', { name: '3. Details' })).toBeInTheDocument();

    await clickBack(user);
    expect(screen.getByRole('heading', { name: '2. Location' })).toBeInTheDocument();

    await clickBack(user);
    expect(screen.getByRole('heading', { name: '1. Photo' })).toBeInTheDocument();
  });

  it('blocks advancing from Details until required fields are valid', async () => {
    renderUpload();
    const user = userEvent.setup();

    await completePhotoStep(user);
    await completeLocationStep(user);

    // No vehicle type / context / legal status chosen yet.
    await clickNext(user);

    expect(await screen.findByText('Select at least one vehicle type')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '3. Details' })).toBeInTheDocument();
  });

  it('shows a read-only summary on the Review step', async () => {
    renderUpload();
    const user = userEvent.setup();

    await advanceToReview(user);

    expect(screen.getByRole('heading', { name: '4. Review' })).toBeInTheDocument();
    expect(screen.getByText('41.010000, 29.020000')).toBeInTheDocument();
    // Vehicle type + legal status surface as summary badges.
    expect(screen.getByText('Street parking')).toBeInTheDocument();
    expect(screen.getByText('spot.jpg')).toBeInTheDocument();
  });

  it('reuses the uploaded media when create fails, then succeeds without re-uploading', async () => {
    vi.mocked(mediaApi.uploadMedia).mockResolvedValue({
          mediaId: createdSpot.mediaId,
          status: 'READY',
          contentType: 'image/jpeg',
          fileSize: 1024,
    });
    vi.mocked(parkingApi.createParkingSpot)
      .mockRejectedValueOnce(new Error('Create failed'))
      .mockResolvedValueOnce(createdSpot);

    renderUpload();
    const user = userEvent.setup();

    await advanceToReview(user);

    // First attempt: upload succeeds, create fails — media is kept for retry.
    await user.click(screen.getByRole('button', { name: 'Upload & create spot' }));
    expect(await screen.findByText(/Your photo was uploaded successfully/)).toBeInTheDocument();

    // Second attempt: create succeeds, photo is reused (no second upload).
    await user.click(screen.getByRole('button', { name: 'Upload & create spot' }));
    expect(await screen.findByRole('heading', { name: 'Spot created' })).toBeInTheDocument();

    expect(mediaApi.uploadMedia).toHaveBeenCalledTimes(1);
    expect(parkingApi.createParkingSpot).toHaveBeenCalledTimes(2);
  });

  it('clears the kept mediaId when the file is replaced, forcing a re-upload', async () => {
    vi.mocked(mediaApi.uploadMedia).mockResolvedValue({
          mediaId: createdSpot.mediaId,
          status: 'READY',
          contentType: 'image/jpeg',
          fileSize: 1024,
    });
    // Create always fails so the uploaded media would otherwise be reused.
    vi.mocked(parkingApi.createParkingSpot).mockRejectedValue(new Error('Create failed'));

    renderUpload();
    const user = userEvent.setup();

    await advanceToReview(user);
    await user.click(screen.getByRole('button', { name: 'Upload & create spot' }));
    expect(await screen.findByText(/Your photo was uploaded successfully/)).toBeInTheDocument();
    expect(mediaApi.uploadMedia).toHaveBeenCalledTimes(1);

    // Back to the Photo step and replace the file — this must clear the kept media.
    await clickBack(user);
    await clickBack(user);
    await clickBack(user);
    expect(screen.getByRole('heading', { name: '1. Photo' })).toBeInTheDocument();
    await user.upload(screen.getByLabelText('Spot photo'), imageFile('replacement.jpg'));

    await clickNext(user);
    await clickNext(user);
    await clickNext(user);
    await user.click(screen.getByRole('button', { name: 'Upload & create spot' }));
    await screen.findAllByText(/Your photo was uploaded successfully/);

    // The replacement file was uploaded again (mediaId was cleared).
    expect(mediaApi.uploadMedia).toHaveBeenCalledTimes(2);
  });

  it('redirects to the new spot after a successful create', async () => {
    vi.mocked(mediaApi.uploadMedia).mockResolvedValue({
          mediaId: createdSpot.mediaId,
          status: 'READY',
          contentType: 'image/jpeg',
          fileSize: 1024,
    });
    vi.mocked(parkingApi.createParkingSpot).mockResolvedValue(createdSpot);

    renderUpload();
    const user = userEvent.setup();

    await advanceToReview(user);
    await user.click(screen.getByRole('button', { name: 'Upload & create spot' }));

    expect(await screen.findByRole('heading', { name: 'Spot created' })).toBeInTheDocument();
    // The success panel redirects after a short delay.
    expect(await screen.findByText('Spot detail', {}, { timeout: 3000 })).toBeInTheDocument();
  });

  it('shows typeahead suggestions when searching for a place on the Location step', async () => {
    server.use(http.get(NOMINATIM_URL, () => HttpResponse.json(valiItems)));

    renderUpload();
    const user = userEvent.setup();

    await completePhotoStep(user);
    await user.type(screen.getByLabelText('Search location'), 'vali');

    expect(await screen.findByText('Vali Nevzat Ayaz Lisesi')).toBeInTheDocument();
    expect(screen.getByText('Vali Konağı Caddesi')).toBeInTheDocument();
  });

  it('fills coordinates and the empty address when a suggestion is selected', async () => {
    server.use(http.get(NOMINATIM_URL, () => HttpResponse.json(valiItems)));

    renderUpload();
    const user = userEvent.setup();

    await completePhotoStep(user);
    await user.type(screen.getByLabelText('Search location'), 'vali');
    await user.click(await screen.findByRole('button', { name: /Vali Nevzat Ayaz Lisesi/ }));

    // Coordinates and the selected-location label reflect the chosen place.
    expect(screen.getByText('38.460000, 27.100000')).toBeInTheDocument();
    expect(screen.getByText(/Selected location: Karşıyaka, İzmir/)).toBeInTheDocument();
    // The empty optional address is filled with the geocoded display name.
    expect(screen.getByLabelText('Address (optional)')).toHaveValue(
      'Vali Nevzat Ayaz Lisesi, Karşıyaka, İzmir',
    );

    // Selecting a place must not create a spot — still on the Location step.
    expect(screen.getByRole('heading', { name: '2. Location' })).toBeInTheDocument();
  });

  it('does not overwrite an address the user already typed', async () => {
    server.use(http.get(NOMINATIM_URL, () => HttpResponse.json(valiItems)));

    renderUpload();
    const user = userEvent.setup();

    await completePhotoStep(user);
    await user.type(screen.getByLabelText('Address (optional)'), 'My custom address');
    await user.type(screen.getByLabelText('Search location'), 'vali');
    await user.click(await screen.findByRole('button', { name: /Vali Nevzat Ayaz Lisesi/ }));

    expect(screen.getByLabelText('Address (optional)')).toHaveValue('My custom address');
  });

  it('updates coordinates when a point is picked on the map', async () => {
    renderUpload();
    const user = userEvent.setup();

    await completePhotoStep(user);
    await user.click(screen.getByRole('button', { name: 'stub-pick-location' }));

    expect(screen.getByText('41.500000, 29.500000')).toBeInTheDocument();
    expect(screen.getByText(/Selected map point/)).toBeInTheDocument();
  });

  it('blocks advancing from Location until coordinates are set', async () => {
    renderUpload();
    const user = userEvent.setup();

    await completePhotoStep(user);
    await clickNext(user);

    // Navigation was blocked — still on the Location step.
    expect(screen.getByRole('heading', { name: '2. Location' })).toBeInTheDocument();
  });
});
