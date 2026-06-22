import type { PublicSpot } from '@parkio/types';
import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { AUTOCOMPLETE_DEBOUNCE_MS } from '@/lib/usePlaceAutocomplete';
import { MapPage } from './MapPage';

// Leaflet can't render in jsdom; stub the map. It exposes the resolved center so
// the fallback viewport can be asserted, plus a button that simulates clicking
// the map to set the search center.
vi.mock('@/components/map/NearbySpotsMap', () => ({
  NearbySpotsMap: ({
    center,
    onPickCenter,
    spots = [],
    onSelectSpot,
  }: {
    center: { lat: number; lng: number };
    onPickCenter: (lat: number, lng: number) => void;
    spots?: PublicSpot[];
    onSelectSpot?: (id: string | null) => void;
  }) => (
    <div>
      <span data-testid="map-center">{`${center.lat},${center.lng}`}</span>
      <button type="button" onClick={() => onPickCenter(41.5, 29.5)}>
        stub-pick-center
      </button>
      {spots[0] ? (
        <button type="button" onClick={() => onSelectSpot?.(spots[0].id)}>
          stub-select-first-spot
        </button>
      ) : null}
    </div>
  ),
}));

/** Replace the browser Geolocation API for a single test. */
function stubGeolocation(value: Partial<Geolocation> | undefined) {
  Object.defineProperty(navigator, 'geolocation', { configurable: true, value });
}

/** Default Nominatim base URL (no VITE override in the test env). */
const NOMINATIM_URL = 'https://nominatim.openstreetmap.org/search';

function nominatimItem(overrides: Record<string, unknown> = {}) {
  return {
    place_id: 1,
    display_name: 'Konak Pier, Konak, İzmir, Türkiye',
    name: 'Konak Pier',
    lat: '38.42',
    lon: '27.14',
    address: { city: 'İzmir', city_district: 'Konak' },
    ...overrides,
  };
}

/** Two "vali" matches used to exercise the typeahead dropdown. */
const valiItems = [
  nominatimItem({
    place_id: 11,
    name: 'Vali Nevzat Ayaz Lisesi',
    display_name: 'Vali Nevzat Ayaz Lisesi, Karşıyaka, İzmir',
    lat: '38.46',
    lon: '27.10',
    address: { city: 'İzmir', city_district: 'Karşıyaka' },
  }),
  nominatimItem({
    place_id: 12,
    name: 'Vali Konağı Caddesi',
    display_name: 'Vali Konağı Caddesi, Konak, İzmir',
    lat: '38.41',
    lon: '27.13',
    address: { city: 'İzmir', city_district: 'Konak' },
  }),
];

/** Real-timer wait used to let the typeahead debounce window elapse. */
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function openSearchOptions(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Filters and search options' }));
}

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
    server.use(
      http.get(`${API_BASE}/users/me/vehicle`, () =>
        HttpResponse.json({ vehicleType: 'SEDAN', plate: '35PK123' }),
      ),
    );
    // Default: geolocation unavailable so the map uses the İzmir fallback and
    // does not auto-search. Individual tests override this as needed.
    stubGeolocation(undefined);
  });

  it('initializes to the İzmir fallback center when geolocation is unavailable', async () => {
    renderWithProviders(<MapPage />);

    expect(await screen.findByTestId('map-center')).toHaveTextContent('38.4237,27.1428');
    // No search runs automatically for the fallback viewport.
    expect(screen.getByText('Search for nearby spots')).toBeInTheDocument();
  });

  it('auto-fills coordinates and searches when geolocation succeeds on mount', async () => {
    server.use(http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])));
    stubGeolocation({
      getCurrentPosition: (success) =>
        success({ coords: { latitude: 38.42, longitude: 27.14 } } as GeolocationPosition),
    });

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    // Coordinates are still synced, but Q4 keeps them behind the compact
    // mobile search options panel.
    await openSearchOptions(user);
    expect(await screen.findByDisplayValue('38.42')).toBeInTheDocument();
    expect(screen.getByLabelText('Longitude')).toHaveValue('27.14');
    // Search ran without the user pressing "Search nearby".
    const cardLink = await screen.findByRole('link', { name: 'Stub Address 7' });
    expect(cardLink).toHaveAttribute('href', `/spots/${spot.id}`);
  });

  it('shows a friendly message and keeps manual search when geolocation is denied', async () => {
    stubGeolocation({
      getCurrentPosition: (_success, error) =>
        error?.({ code: 1, PERMISSION_DENIED: 1 } as GeolocationPositionError),
    });

    renderWithProviders(<MapPage />);

    expect(
      await screen.findByText('Location permission was not granted. You can search manually.'),
    ).toBeInTheDocument();
    // Fallback viewport remains and manual search is still available.
    expect(screen.getByTestId('map-center')).toHaveTextContent('38.4237,27.1428');
    const user = userEvent.setup();
    await openSearchOptions(user);
    expect(screen.getByRole('button', { name: 'Search nearby' })).toBeInTheDocument();
  });

  it('fills the coordinate fields when a location is picked on the map', async () => {
    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'stub-pick-center' }));
    await openSearchOptions(user);

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
    await openSearchOptions(user);
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));

    const cardLink = await screen.findByRole('link', { name: 'Stub Address 7' });
    expect(cardLink).toBeInTheDocument();
    expect(cardLink).toHaveAttribute('href', `/spots/${spot.id}`);
  });

  it('renders the mobile bottom sheet discovery surface with vehicle compatibility', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'stub-pick-center' }));
    expect(screen.getByRole('button', { name: /Search results, collapsed/ })).toBeInTheDocument();
    await openSearchOptions(user);
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));

    expect(await screen.findByRole('complementary', { name: 'Search results' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Search results, half/ })).toBeInTheDocument();
    expect(await screen.findByText('Fits your Sedan')).toBeInTheDocument();
  });

  it('shows an empty state when no spots are found', async () => {
    server.use(http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([])));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'stub-pick-center' }));
    await openSearchOptions(user);
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));

    // "No spots nearby" appears in both the bottom-sheet peek summary and the
    // empty-state body, so assert the empty-state description is present too.
    expect(await screen.findByText(/No spots found in this area/)).toBeInTheDocument();
    expect(screen.getAllByText('No spots nearby').length).toBeGreaterThanOrEqual(1);
  });

  it('does not call geocoding for queries shorter than 3 characters', async () => {
    let geocodeCalls = 0;
    server.use(
      http.get(NOMINATIM_URL, () => {
        geocodeCalls += 1;
        return HttpResponse.json(valiItems);
      }),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Search location'), 'va');
    await wait(AUTOCOMPLETE_DEBOUNCE_MS + 150);

    expect(geocodeCalls).toBe(0);
    expect(screen.queryByText('Vali Nevzat Ayaz Lisesi')).not.toBeInTheDocument();
  });

  it('shows debounced typeahead suggestions after typing 3+ characters', async () => {
    server.use(http.get(NOMINATIM_URL, () => HttpResponse.json(valiItems)));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Search location'), 'vali');
    // Loading state appears before the debounce fires.
    expect(screen.getByText('Searching…')).toBeInTheDocument();

    expect(await screen.findByText('Vali Nevzat Ayaz Lisesi')).toBeInTheDocument();
    expect(screen.getByText('Vali Konağı Caddesi')).toBeInTheDocument();
  });

  it('shows "No places found" when the typeahead returns nothing', async () => {
    server.use(http.get(NOMINATIM_URL, () => HttpResponse.json([])));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Search location'), 'zzqqww');

    expect(await screen.findByText('No places found')).toBeInTheDocument();
  });

  it('shows a friendly error when typeahead geocoding fails', async () => {
    server.use(http.get(NOMINATIM_URL, () => HttpResponse.error()));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Search location'), 'vali');

    expect(await screen.findByText('Could not load suggestions')).toBeInTheDocument();
  });

  it('selecting a suggestion runs the parking nearby search at its coordinates', async () => {
    let nearbyUrl: URL | null = null;
    server.use(
      http.get(NOMINATIM_URL, () => HttpResponse.json(valiItems)),
      http.get(`${API_BASE}/parking/spots/nearby`, ({ request }) => {
        nearbyUrl = new URL(request.url);
        return HttpResponse.json([spot]);
      }),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Search location'), 'vali');
    await user.click(await screen.findByRole('button', { name: /Vali Nevzat Ayaz Lisesi/ }));

    const cardLink = await screen.findByRole('link', { name: 'Stub Address 7' });
    expect(cardLink).toHaveAttribute('href', `/spots/${spot.id}`);
    expect(nearbyUrl?.searchParams.get('lat')).toBe('38.46');
    expect(nearbyUrl?.searchParams.get('lng')).toBe('27.1');
    expect(screen.getByText(/Near Karşıyaka, İzmir/)).toBeInTheDocument();
  });

  it('supports keyboard navigation (ArrowDown + Enter selects a suggestion)', async () => {
    let nearbyUrl: URL | null = null;
    server.use(
      http.get(NOMINATIM_URL, () => HttpResponse.json(valiItems)),
      http.get(`${API_BASE}/parking/spots/nearby`, ({ request }) => {
        nearbyUrl = new URL(request.url);
        return HttpResponse.json([spot]);
      }),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Search location'), 'vali');
    await screen.findByText('Vali Nevzat Ayaz Lisesi');
    await user.keyboard('{ArrowDown}{Enter}');

    const cardLink = await screen.findByRole('link', { name: 'Stub Address 7' });
    expect(cardLink).toBeInTheDocument();
    expect(nearbyUrl?.searchParams.get('lat')).toBe('38.46');
    expect(screen.getByLabelText('Search location')).toHaveValue('Vali Nevzat Ayaz Lisesi');
  });

  it('closes the suggestions dropdown on Escape', async () => {
    server.use(http.get(NOMINATIM_URL, () => HttpResponse.json(valiItems)));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Search location'), 'vali');
    expect(await screen.findByText('Vali Nevzat Ayaz Lisesi')).toBeInTheDocument();

    await user.keyboard('{Escape}');

    expect(screen.queryByText('Vali Nevzat Ayaz Lisesi')).not.toBeInTheDocument();
  });

  it('ignores stale typeahead responses when the query changes', async () => {
    server.use(
      http.get(NOMINATIM_URL, async ({ request }) => {
        const q = new URL(request.url).searchParams.get('q');
        if (q === 'vali') {
          await delay(400);
          return HttpResponse.json([nominatimItem({ place_id: 99, name: 'Stale Vali Result' })]);
        }
        return HttpResponse.json([nominatimItem({ place_id: 100, name: 'Fresh Valide Result' })]);
      }),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    const input = screen.getByLabelText('Search location');
    await user.type(input, 'vali');
    // Let the debounce fire so the slow "vali" request is genuinely in-flight.
    await wait(AUTOCOMPLETE_DEBOUNCE_MS + 80);
    await user.type(input, 'de'); // -> "valide" aborts the in-flight "vali" request

    expect(await screen.findByText('Fresh Valide Result')).toBeInTheDocument();
    // Give the slow "vali" response time to (not) arrive.
    await wait(500);
    expect(screen.queryByText('Stale Vali Result')).not.toBeInTheDocument();
  });

  it('runs geocode-on-submit when Enter is pressed with no suggestion highlighted', async () => {
    let nearbyUrl: URL | null = null;
    server.use(
      http.get(NOMINATIM_URL, () => HttpResponse.json([nominatimItem()])),
      http.get(`${API_BASE}/parking/spots/nearby`, ({ request }) => {
        nearbyUrl = new URL(request.url);
        return HttpResponse.json([spot]);
      }),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    // Submitting with no highlighted suggestion runs the geocode-on-submit path
    // (single match auto-selects). fireEvent.submit mirrors the Enter key here
    // because jsdom does not perform implicit form submission.
    await user.type(screen.getByLabelText('Search location'), 'Konak Pier');
    fireEvent.submit(screen.getByRole('search'));

    const cardLink = await screen.findByRole('link', { name: 'Stub Address 7' });
    expect(cardLink).toBeInTheDocument();
    expect(nearbyUrl?.searchParams.get('lat')).toBe('38.42');
  });

  it('still supports advanced manual coordinates', async () => {
    server.use(http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await openSearchOptions(user);
    await user.type(screen.getByLabelText('Latitude'), '41.0');
    await user.type(screen.getByLabelText('Longitude'), '29.0');
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));

    expect(await screen.findByRole('link', { name: 'Stub Address 7' })).toBeInTheDocument();
  });

  it('fills coordinates when "Use my location" is clicked', async () => {
    server.use(http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([])));
    stubGeolocation({
      getCurrentPosition: (success) =>
        success({ coords: { latitude: 38.5, longitude: 27.2 } } as GeolocationPosition),
    });

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Use my location' }));
    await openSearchOptions(user);

    expect(screen.getByLabelText('Latitude')).toHaveValue('38.5');
    expect(screen.getByLabelText('Longitude')).toHaveValue('27.2');
  });

  it('preserves the current bottom sheet state when a marker is selected', async () => {
    server.use(http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'stub-pick-center' }));
    await openSearchOptions(user);
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));
    expect(await screen.findByRole('button', { name: /Search results, half/ })).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: 'stub-select-first-spot' }));

    expect(screen.getByTestId('selected-spot-preview')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Search results, half/ })).toBeInTheDocument();
  });
});
