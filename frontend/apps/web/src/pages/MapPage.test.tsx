import type { GeocodeResult, PublicSpot, SmartReturnSettings } from '@parkio/types';
import { fireEvent, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WebAppRuntime } from '@/app/runtime';
import { parkingKeys } from '@/data/keys';
import { clearUserSessionQueries } from '@/data/sessionQueryCache';
import * as toast from '@/lib/toast';
import { AUTOCOMPLETE_DEBOUNCE_MS } from '@/lib/usePlaceAutocomplete';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { makeMunicipalFacility } from '@/test/municipalFixtures';
import {
  createTestAppRuntime,
  renderWithProviders as renderWithBaseProviders,
  resetAuth,
  signInAs,
} from '@/test/utils';
import { MapPage } from './MapPage';

let runtime: WebAppRuntime;
const TEST_NOW = new Date('2026-07-25T22:00:00.000Z');

beforeEach(() => {
  // Parking-session fixtures below are intended to be 12h old. Pin Date without
  // replacing real timers so debounce and user-event behavior remain representative.
  vi.setSystemTime(TEST_NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

function renderWithProviders(
  ui: Parameters<typeof renderWithBaseProviders>[0],
  options: Parameters<typeof renderWithBaseProviders>[1] = {},
) {
  return renderWithBaseProviders(ui, { ...options, runtime });
}

// Leaflet can't render in jsdom; stub the map. It exposes the resolved center so
// the fallback viewport can be asserted, plus a button that simulates clicking
// the map to set the search center.
vi.mock('@/components/map/NearbySpotsMap', () => ({
  NearbySpotsMap: ({
    center,
    onPickCenter,
    spots = [],
    municipalFacilities = [],
    onSelectSpot,
    onSelectMunicipalFacility,
    parkedCar,
    parkedCarSelected,
    onSelectParkedCar,
    onFocusParkedCar,
    ariaLabel,
    ariaDescription,
    selectionSummary,
  }: {
    center: { lat: number; lng: number };
    onPickCenter: (lat: number, lng: number) => void;
    spots?: PublicSpot[];
    municipalFacilities?: { id: string }[];
    onSelectSpot?: (id: string | null) => void;
    onSelectMunicipalFacility?: (id: string | null) => void;
    parkedCar?: { latitude: number; longitude: number } | null;
    parkedCarSelected?: boolean;
    onSelectParkedCar?: () => void;
    onFocusParkedCar?: () => void;
    ariaLabel?: string;
    ariaDescription?: string;
    selectionSummary?: string | null;
  }) => (
    <div role="region" aria-label={ariaLabel} aria-describedby="stub-map-description stub-map-selection">
      <span id="stub-map-description">{ariaDescription}</span>
      <span id="stub-map-selection" data-testid="stub-map-selection" role="status" aria-live="polite">
        {selectionSummary}
      </span>
      <span data-testid="map-center">{`${center.lat},${center.lng}`}</span>
      <span data-testid="stub-spot-count">{spots.length}</span>
      <span data-testid="stub-municipal-count">{municipalFacilities.length}</span>
      <button type="button" onClick={() => onPickCenter(41.5, 29.5)}>
        stub-pick-center
      </button>
      {spots[0] ? (
        <button type="button" onClick={() => onSelectSpot?.(spots[0].id)}>
          stub-select-first-spot
        </button>
      ) : null}
      {municipalFacilities[0] ? (
        <button type="button" onClick={() => onSelectMunicipalFacility?.(municipalFacilities[0].id)}>
          stub-select-first-facility
        </button>
      ) : null}
      {parkedCar ? (
        <>
          <span
            data-testid="stub-parked-car"
            data-lat={parkedCar.latitude}
            data-lng={parkedCar.longitude}
            data-selected={parkedCarSelected ? 'true' : 'false'}
          />
          <button type="button" onClick={() => onSelectParkedCar?.()}>
            stub-select-parked-car
          </button>
          <button type="button" onClick={() => onFocusParkedCar?.()}>
            stub-recenter-parked-car
          </button>
        </>
      ) : null}
    </div>
  ),
}));

/** Replace the browser Geolocation API for a single test. */
function stubGeolocation(value: Partial<Geolocation> | undefined) {
  Object.defineProperty(navigator, 'geolocation', { configurable: true, value });
}

const originalGeolocation = navigator.geolocation;

function restoreGeolocation() {
  stubGeolocation(originalGeolocation);
}

/** Wait until discovery chrome leaves the loading summary copy. */
async function expectSettledMapSheetSummary(text: string | RegExp) {
  await waitFor(() => {
    expect(screen.getByTestId('map-sheet-summary')).toHaveTextContent(text);
  });
}

/** Wait until municipal list data settles (section shell can render while loading). */
async function expectMunicipalFacilityLoaded(displayName: string, expectedMapCount: string) {
  expect(await screen.findByText(displayName)).toBeInTheDocument();
  await waitFor(() => {
    expect(screen.getByTestId('stub-municipal-count')).toHaveTextContent(expectedMapCount);
    expect(screen.queryByTestId('municipal-facility-loading')).not.toBeInTheDocument();
  });
}

/** Backend geocoding endpoint — the browser no longer calls Nominatim directly (ADR-014). */
const GEOCODING_URL = `${API_BASE}/geocoding/search`;

/** Already-mapped GeocodeResult, as the backend now returns it (server-side mapping). */
function geocodeResult(overrides: Partial<GeocodeResult> = {}): GeocodeResult {
  return {
    id: '1',
    displayName: 'Konak Pier, Konak, İzmir, Türkiye',
    primary: 'Konak Pier',
    secondary: 'Konak, İzmir',
    lat: 38.42,
    lng: 27.14,
    ...overrides,
  };
}

/** Two "vali" matches used to exercise the typeahead dropdown. */
const valiItems: GeocodeResult[] = [
  geocodeResult({
    id: '11',
    primary: 'Vali Nevzat Ayaz Lisesi',
    secondary: 'Karşıyaka, İzmir',
    displayName: 'Vali Nevzat Ayaz Lisesi, Karşıyaka, İzmir',
    lat: 38.46,
    lng: 27.1,
  }),
  geocodeResult({
    id: '12',
    primary: 'Vali Konağı Caddesi',
    secondary: 'Konak, İzmir',
    displayName: 'Vali Konağı Caddesi, Konak, İzmir',
    lat: 38.41,
    lng: 27.13,
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

const smartReturnSettings: SmartReturnSettings = {
  enabled: true,
  homeLatitude: 38.4237,
  homeLongitude: 27.1428,
  homeLabel: 'Konak',
  defaultReturnTime: '18:30',
  reminderLeadMinutes: 15,
  lastPromptDate: '2026-06-28',
  todayStatus: 'LEFT_BY_CAR',
  todayExpectedReturnAt: '2026-06-28T19:00:00Z',
  todayReturnCheckCompletedAt: null,
  todayNotificationSentAt: null,
};

describe('MapPage', () => {
  beforeEach(() => {
    runtime = createTestAppRuntime();
    signInAs(runtime, ['USER']);
    // Shell unread badge fetches notifications on mount when AppShell is rendered.
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
    server.use(
      http.get(`${API_BASE}/users/me/vehicle`, () =>
        HttpResponse.json({ vehicleType: 'SEDAN', plate: '35PK123' }),
      ),
    );
    // Default: no ACTIVE Parking Session (HTTP 204). Individual tests override.
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => new HttpResponse(null, { status: 204 })),
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

  it('opens Smart Return map view from the current user saved home area without geolocation', async () => {
    let geolocationCalled = false;
    let nearbyUrl: URL | null = null;
    stubGeolocation({
      getCurrentPosition: () => {
        geolocationCalled = true;
      },
    });
    server.use(
      http.get(`${API_BASE}/users/me/smart-return`, () => HttpResponse.json(smartReturnSettings)),
      http.get(`${API_BASE}/parking/spots/nearby`, ({ request }) => {
        nearbyUrl = new URL(request.url);
        return HttpResponse.json([spot]);
      }),
    );

    renderWithProviders(<MapPage />, { initialEntries: ['/map?smartReturn=1'] });

    expect(await screen.findByText('Showing parking near your saved home.')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Stub Address 7' })).toBeInTheDocument();
    expect(screen.getByTestId('map-center')).toHaveTextContent('38.4237,27.1428');
    expect(nearbyUrl?.searchParams.get('lat')).toBe('38.4237');
    expect(nearbyUrl?.searchParams.get('lng')).toBe('27.1428');
    expect(geolocationCalled).toBe(false);
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
      http.get(GEOCODING_URL, () => {
        geocodeCalls += 1;
        return HttpResponse.json({ results: valiItems });
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
    server.use(http.get(GEOCODING_URL, () => HttpResponse.json({ results: valiItems })));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Search location'), 'vali');
    // Loading state appears before the debounce fires.
    expect(screen.getByText('Searching…')).toBeInTheDocument();

    expect(await screen.findByText('Vali Nevzat Ayaz Lisesi')).toBeInTheDocument();
    expect(screen.getByText('Vali Konağı Caddesi')).toBeInTheDocument();
  });

  it('shows "No places found" when the typeahead returns nothing', async () => {
    server.use(http.get(GEOCODING_URL, () => HttpResponse.json({ results: [] })));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Search location'), 'zzqqww');

    expect(await screen.findByText('No places found')).toBeInTheDocument();
  });

  it('shows a friendly error when typeahead geocoding fails', async () => {
    server.use(http.get(GEOCODING_URL, () => HttpResponse.error()));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Search location'), 'vali');

    expect(await screen.findByText('Could not load suggestions')).toBeInTheDocument();
  });

  it('selecting a suggestion runs the parking nearby search at its coordinates', async () => {
    let nearbyUrl: URL | null = null;
    server.use(
      http.get(GEOCODING_URL, () => HttpResponse.json({ results: valiItems })),
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
      http.get(GEOCODING_URL, () => HttpResponse.json({ results: valiItems })),
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
    server.use(http.get(GEOCODING_URL, () => HttpResponse.json({ results: valiItems })));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Search location'), 'vali');
    expect(await screen.findByText('Vali Nevzat Ayaz Lisesi')).toBeInTheDocument();

    await user.keyboard('{Escape}');

    expect(screen.queryByText('Vali Nevzat Ayaz Lisesi')).not.toBeInTheDocument();
  });

  it('ignores stale typeahead responses when the query changes', async () => {
    server.use(
      http.get(GEOCODING_URL, async ({ request }) => {
        const q = new URL(request.url).searchParams.get('q');
        if (q === 'vali') {
          await delay(400);
          return HttpResponse.json({ results: [geocodeResult({ id: '99', primary: 'Stale Vali Result' })] });
        }
        return HttpResponse.json({ results: [geocodeResult({ id: '100', primary: 'Fresh Valide Result' })] });
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
      http.get(GEOCODING_URL, () => HttpResponse.json({ results: [geocodeResult()] })),
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

  it('collapses the sheet to its peek so the preview never obscures it (mobile)', async () => {
    // On mobile the selected-spot preview and the results sheet share the bottom
    // band. Selecting a marker drops the sheet to its always-visible peek so the
    // two never overlap and the drag handle stays reachable just below the preview.
    server.use(http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'stub-pick-center' }));
    await openSearchOptions(user);
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));
    expect(await screen.findByRole('button', { name: /Search results, half/ })).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: 'stub-select-first-spot' }));

    // Preview is shown, and the sheet has collapsed to its peek (handle still present).
    expect(screen.getByTestId('selected-spot-preview')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Search results, collapsed/ })).toBeInTheDocument();
  });

  it('hides the preview once the user expands the sheet to browse results (mobile)', async () => {
    // If the user deliberately raises the sheet (collapsed → half) to scan the
    // list, the preview must yield the bottom band rather than cover the results.
    server.use(http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'stub-pick-center' }));
    await openSearchOptions(user);
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));
    await user.click(await screen.findByRole('button', { name: 'stub-select-first-spot' }));
    expect(screen.getByTestId('selected-spot-preview')).toBeInTheDocument();

    // Tapping the handle cycles collapsed → half; the preview steps aside.
    await user.click(screen.getByRole('button', { name: /Search results, collapsed/ }));
    expect(screen.queryByTestId('selected-spot-preview')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Search results, half/ })).toBeInTheDocument();
  });
});

const activeParkingSession = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  status: 'ACTIVE' as const,
  parkingSource: 'MANUAL' as const,
  startedAt: '2026-07-25T10:00:00.000Z',
  endedAt: null,
  latitude: 38.42,
  longitude: 27.14,
  estimatedFee: null,
  lastConfirmedAt: '2026-07-25T10:00:00.000Z',
  completionType: null,
};

const parkingSessionLifecycleConfig = {
  confirmAfterMs: 86_400_000,
  reminder2AfterMs: 172_800_000,
  autoCompleteAfterMs: 259_200_000,
  confirmAfter: 'PT24H',
  reminder2After: 'PT48H',
  autoCompleteAfter: 'PT72H',
  remindersEnabled: true,
  autoCompleteEnabled: true,
};

describe('MapPage Parking Session (ACTIVE restore)', () => {
  beforeEach(() => {
    runtime = createTestAppRuntime();
    signInAs(runtime, ['USER']);
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
    server.use(
      http.get(`${API_BASE}/users/me/vehicle`, () =>
        HttpResponse.json({ vehicleType: 'SEDAN', plate: '35PK123' }),
      ),
    );
    server.use(
      http.get(`${API_BASE}/parking/sessions/lifecycle-config`, () =>
        HttpResponse.json(parkingSessionLifecycleConfig),
      ),
    );
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => new HttpResponse(null, { status: 204 })),
    );
    stubGeolocation(undefined);
  });

  it('does not fetch or render an active session for unauthenticated users', async () => {
    resetAuth(runtime);
    let activeFetched = false;
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => {
        activeFetched = true;
        return HttpResponse.json(activeParkingSession);
      }),
    );

    renderWithProviders(<MapPage />);
    await screen.findByTestId('map-center');
    await wait(50);

    expect(activeFetched).toBe(false);
    expect(screen.queryByTestId('active-parking-session-card')).not.toBeInTheDocument();
    expect(screen.queryByTestId('stub-parked-car')).not.toBeInTheDocument();
  });

  it('renders neither card nor marker when active session is 204/null', async () => {
    renderWithProviders(<MapPage />);
    await screen.findByTestId('map-center');
    await wait(50);

    expect(screen.queryByTestId('active-parking-session-card')).not.toBeInTheDocument();
    expect(screen.queryByTestId('stub-parked-car')).not.toBeInTheDocument();
  });

  it('restores the Active card and parked-car marker from GET active', async () => {
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => HttpResponse.json(activeParkingSession)),
    );

    renderWithProviders(<MapPage />);

    expect(await screen.findByTestId('active-parking-session-card')).toBeInTheDocument();
    expect(screen.getByTestId('stub-parked-car')).toHaveAttribute('data-lat', '38.42');
    expect(screen.getByTestId('stub-parked-car')).toHaveAttribute('data-lng', '27.14');
    expect(screen.getByText('Saved by you')).toBeInTheDocument();
  });

  it('does not flash Active UI while the active query is still pending', async () => {
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, async () => {
        await delay(250);
        return HttpResponse.json(activeParkingSession);
      }),
    );

    renderWithProviders(<MapPage />);
    await screen.findByTestId('map-center');

    expect(screen.queryByTestId('active-parking-session-card')).not.toBeInTheDocument();
    expect(screen.queryByTestId('stub-parked-car')).not.toBeInTheDocument();

    expect(await screen.findByTestId('active-parking-session-card')).toBeInTheDocument();
  });

  it('shares map-focus between card CTA, marker click, and recenter control', async () => {
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => HttpResponse.json(activeParkingSession)),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();
    await screen.findByTestId('active-parking-session-card');
    expect(screen.getByTestId('stub-parked-car')).toHaveAttribute('data-selected', 'false');

    await user.click(screen.getByTestId('active-parking-find-my-car'));
    expect(screen.getByTestId('stub-parked-car')).toHaveAttribute('data-selected', 'true');

    // Clear emphasis via spot selection, then re-focus from marker.
    server.use(http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])));
    await user.click(screen.getByRole('button', { name: 'stub-pick-center' }));
    await openSearchOptions(user);
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));
    await user.click(await screen.findByRole('button', { name: 'stub-select-first-spot' }));
    expect(screen.getByTestId('stub-parked-car')).toHaveAttribute('data-selected', 'false');

    await user.click(screen.getByRole('button', { name: 'stub-select-parked-car' }));
    expect(screen.getByTestId('stub-parked-car')).toHaveAttribute('data-selected', 'true');

    await user.click(screen.getByRole('button', { name: 'stub-select-first-spot' }));
    expect(screen.getByTestId('stub-parked-car')).toHaveAttribute('data-selected', 'false');
    await user.click(screen.getByRole('button', { name: 'stub-recenter-parked-car' }));
    expect(screen.getByTestId('stub-parked-car')).toHaveAttribute('data-selected', 'true');
  });

  it('does not open Spot Detail from the parked-car marker', async () => {
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => HttpResponse.json(activeParkingSession)),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();
    await screen.findByTestId('active-parking-session-card');
    await user.click(screen.getByRole('button', { name: 'stub-select-parked-car' }));

    expect(screen.queryByRole('link', { name: /view spot details/i })).not.toBeInTheDocument();
    expect(screen.getByTestId('active-parking-session-card')).toBeInTheDocument();
  });

  it('renders COMMUNITY source label for claim-originated sessions', async () => {
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () =>
        HttpResponse.json({ ...activeParkingSession, parkingSource: 'COMMUNITY' }),
      ),
    );

    renderWithProviders(<MapPage />);
    expect(await screen.findByTestId('active-parking-source')).toHaveTextContent(
      'From a claimed spot',
    );
  });

  it('coexists with SelectedSpotPreview when a nearby spot is also selected', async () => {
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => HttpResponse.json(activeParkingSession)),
    );
    server.use(http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])));

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    expect(await screen.findByTestId('active-parking-session-card')).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: 'stub-pick-center' }));
    await openSearchOptions(user);
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));
    await user.click(await screen.findByRole('button', { name: 'stub-select-first-spot' }));

    expect(screen.getByTestId('active-parking-session-card')).toBeInTheDocument();
    expect(screen.getByTestId('selected-spot-preview')).toBeInTheDocument();
  });

  it('clears the Active card after logout without retaining precise coordinates', async () => {
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => HttpResponse.json(activeParkingSession)),
    );

    const { queryClient } = renderWithProviders(<MapPage />);
    expect(await screen.findByTestId('stub-parked-car')).toBeInTheDocument();

    act(() => {
      resetAuth(runtime);
      // Production mounts SessionQueryCacheSync; mirror logout teardown here.
      clearUserSessionQueries(queryClient);
    });

    await waitFor(() => {
      expect(screen.queryByTestId('active-parking-session-card')).not.toBeInTheDocument();
      expect(screen.queryByTestId('stub-parked-car')).not.toBeInTheDocument();
    });
  });

  it('keeps the Active card when coordinates are unusable and hides Park Here', async () => {
    const coords = await import('@/components/map/parkedCarCoords');
    const spy = vi.spyOn(coords, 'isUsableParkedCoordinate').mockReturnValue(false);
    try {
      server.use(
        http.get(`${API_BASE}/parking/sessions/active`, () => HttpResponse.json(activeParkingSession)),
      );

      renderWithProviders(<MapPage />);
      expect(await screen.findByTestId('active-parking-session-card')).toBeInTheDocument();
      expect(screen.queryByTestId('stub-parked-car')).not.toBeInTheDocument();
      expect(screen.queryByTestId('park-here-start')).not.toBeInTheDocument();
    } finally {
      spy.mockRestore();
    }
  });
});

describe('MapPage Parking Session (Park Here)', () => {
  beforeEach(() => {
    runtime = createTestAppRuntime();
    signInAs(runtime, ['USER']);
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
    server.use(
      http.get(`${API_BASE}/users/me/vehicle`, () =>
        HttpResponse.json({ vehicleType: 'SEDAN', plate: '35PK123' }),
      ),
    );
    server.use(
      http.get(`${API_BASE}/parking/sessions/lifecycle-config`, () =>
        HttpResponse.json(parkingSessionLifecycleConfig),
      ),
    );
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => new HttpResponse(null, { status: 204 })),
    );
    // Unavailable mount geolocation keeps the mobile sheet collapsed so the
    // bottom Parking Session stack (Park Here / Active card) stays visible.
    stubGeolocation(undefined);
    vi.spyOn(toast, 'showSuccess').mockImplementation(() => undefined);
    vi.spyOn(toast, 'showError').mockImplementation(() => undefined);
    vi.spyOn(toast, 'showInfo').mockImplementation(() => undefined);
  });

  /** Park Here acquires location on click — enable GPS only for the start action. */
  function stubParkHereLocation(
    coords: { latitude: number; longitude: number } = { latitude: 38.42, longitude: 27.14 },
  ) {
    stubGeolocation({
      getCurrentPosition: (success) =>
        success({ coords } as GeolocationPosition),
    });
  }

  it('does not show Park Here for guests', async () => {
    resetAuth(runtime);
    renderWithProviders(<MapPage />);
    await screen.findByTestId('map-center');
    await wait(50);

    expect(screen.queryByTestId('park-here-start')).not.toBeInTheDocument();
  });

  it('shows Park Here when authenticated with no ACTIVE session', async () => {
    renderWithProviders(<MapPage />);
    expect(await screen.findByTestId('park-here-start')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /start a parking session/i })).toBeInTheDocument();
  });

  it('does not flash Park Here while the active query is pending', async () => {
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, async () => {
        await delay(250);
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderWithProviders(<MapPage />);
    await screen.findByTestId('map-center');

    expect(screen.queryByTestId('park-here-start')).not.toBeInTheDocument();
    expect(await screen.findByTestId('park-here-start')).toBeInTheDocument();
  });

  it('hides Park Here while ACTIVE exists and restores it after the session clears', async () => {
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => HttpResponse.json(activeParkingSession)),
    );

    const { queryClient } = renderWithProviders(<MapPage />);
    expect(await screen.findByTestId('active-parking-session-card')).toBeInTheDocument();
    expect(screen.queryByTestId('park-here-start')).not.toBeInTheDocument();

    act(() => {
      queryClient.setQueryData(parkingKeys.activeSession(), null);
    });

    expect(await screen.findByTestId('park-here-start')).toBeInTheDocument();
    expect(screen.queryByTestId('active-parking-session-card')).not.toBeInTheDocument();
  });

  it('hides Park Here for COMMUNITY claim-originated ACTIVE sessions', async () => {
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () =>
        HttpResponse.json({ ...activeParkingSession, parkingSource: 'COMMUNITY' }),
      ),
    );

    renderWithProviders(<MapPage />);
    expect(await screen.findByTestId('active-parking-session-card')).toBeInTheDocument();
    expect(screen.queryByTestId('park-here-start')).not.toBeInTheDocument();
  });

  it('creates a session from Park Here and restores card + marker without auto-flying', async () => {
    let startCalls = 0;
    server.use(
      http.post(`${API_BASE}/parking/sessions`, async ({ request }) => {
        startCalls += 1;
        const body = (await request.json()) as { latitude: number; longitude: number };
        expect(body).toEqual({ latitude: 38.42, longitude: 27.14 });
        return HttpResponse.json(activeParkingSession, { status: 201 });
      }),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();
    expect(await screen.findByTestId('park-here-start')).toBeInTheDocument();

    stubParkHereLocation();
    await user.click(screen.getByTestId('park-here-cta'));

    expect(await screen.findByTestId('active-parking-session-card')).toBeInTheDocument();
    expect(screen.getByTestId('stub-parked-car')).toHaveAttribute('data-lat', '38.42');
    expect(screen.queryByTestId('park-here-start')).not.toBeInTheDocument();
    expect(toast.showSuccess).toHaveBeenCalledWith('Parking location saved.');
    expect(startCalls).toBe(1);
  });

  it('recovers from 409 by restoring ACTIVE UI and a friendly toast', async () => {
    let activeGets = 0;
    server.use(
      http.post(`${API_BASE}/parking/sessions`, () =>
        HttpResponse.json(
          apiErrorBody('ACTIVE_PARKING_SESSION_EXISTS', 'Active session exists'),
          { status: 409 },
        ),
      ),
      http.get(`${API_BASE}/parking/sessions/active`, () => {
        activeGets += 1;
        if (activeGets === 1) {
          return new HttpResponse(null, { status: 204 });
        }
        return HttpResponse.json(activeParkingSession);
      }),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();
    expect(await screen.findByTestId('park-here-start')).toBeInTheDocument();

    stubParkHereLocation();
    await user.click(screen.getByTestId('park-here-cta'));

    expect(await screen.findByTestId('active-parking-session-card')).toBeInTheDocument();
    expect(screen.getByTestId('stub-parked-car')).toBeInTheDocument();
    expect(screen.queryByTestId('park-here-start')).not.toBeInTheDocument();
    expect(toast.showInfo).toHaveBeenCalledWith('You already have an active parking session.');
    expect(toast.showError).not.toHaveBeenCalled();
    expect(activeGets).toBeGreaterThanOrEqual(2);
  });
});

describe('MapPage Parking Session (terminal actions)', () => {
  beforeEach(() => {
    runtime = createTestAppRuntime();
    signInAs(runtime, ['USER']);
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
    server.use(
      http.get(`${API_BASE}/users/me/vehicle`, () =>
        HttpResponse.json({ vehicleType: 'SEDAN', plate: '35PK123' }),
      ),
    );
    server.use(
      http.get(`${API_BASE}/parking/sessions/lifecycle-config`, () =>
        HttpResponse.json(parkingSessionLifecycleConfig),
      ),
    );
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => HttpResponse.json(activeParkingSession)),
    );
    stubGeolocation(undefined);
    vi.spyOn(toast, 'showSuccess').mockImplementation(() => undefined);
    vi.spyOn(toast, 'showError').mockImplementation(() => undefined);
    vi.spyOn(toast, 'showInfo').mockImplementation(() => undefined);
  });

  it('completing a session clears card/marker and restores Park Here', async () => {
    server.use(
      http.post(
        `${API_BASE}/parking/sessions/${activeParkingSession.id}/complete`,
        () =>
          HttpResponse.json({
            ...activeParkingSession,
            status: 'COMPLETED',
            endedAt: '2026-07-25T11:00:00.000Z',
          }),
      ),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    expect(await screen.findByTestId('active-parking-session-card')).toBeInTheDocument();
    expect(screen.getByTestId('stub-parked-car')).toBeInTheDocument();
    expect(screen.queryByTestId('park-here-start')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('active-parking-leave'));
    await user.click(screen.getByTestId('active-parking-confirm-leave'));

    expect(await screen.findByTestId('park-here-start')).toBeInTheDocument();
    expect(screen.queryByTestId('active-parking-session-card')).not.toBeInTheDocument();
    expect(screen.queryByTestId('stub-parked-car')).not.toBeInTheDocument();
    expect(toast.showSuccess).toHaveBeenCalledWith('Parking session finished.');
  });

  it('cancelling a session clears card/marker and restores Park Here', async () => {
    server.use(
      http.post(
        `${API_BASE}/parking/sessions/${activeParkingSession.id}/cancel`,
        () =>
          HttpResponse.json({
            ...activeParkingSession,
            status: 'CANCELLED',
            endedAt: '2026-07-25T11:00:00.000Z',
          }),
      ),
    );

    renderWithProviders(<MapPage />);
    const user = userEvent.setup();

    expect(await screen.findByTestId('active-parking-session-card')).toBeInTheDocument();

    await user.click(screen.getByTestId('active-parking-cancel'));
    await user.click(screen.getByTestId('active-parking-confirm-cancel'));

    expect(await screen.findByTestId('park-here-start')).toBeInTheDocument();
    expect(screen.queryByTestId('active-parking-session-card')).not.toBeInTheDocument();
    expect(toast.showSuccess).toHaveBeenCalledWith('Parking session cancelled.');
  });
});

describe('MapPage municipal discovery (WEB-MUNI-01)', () => {
  beforeEach(() => {
    runtime = createTestAppRuntime();
    signInAs(runtime, ['USER']);
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
    server.use(
      http.get(`${API_BASE}/users/me/vehicle`, () =>
        HttpResponse.json({ vehicleType: 'SEDAN', plate: '35PK123' }),
      ),
    );
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => new HttpResponse(null, { status: 204 })),
    );
    server.use(
      http.get(`${API_BASE}/parking/sessions/lifecycle-config`, () =>
        HttpResponse.json({
          confirmAfterMs: 43_200_000,
          reminderLeadMs: 3_600_000,
          autoCompleteAfterMs: 86_400_000,
        }),
      ),
    );
    stubGeolocation(undefined);
  });

  afterEach(() => {
    clearUserSessionQueries(runtime.queryClient);
    resetAuth(runtime);
    restoreGeolocation();
  });

  it('does not call facilities API when the feature flag is off', async () => {
    let facilitiesHits = 0;
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => {
        facilitiesHits += 1;
        return HttpResponse.json([]);
      }),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled={false} />);
    const user = userEvent.setup();
    await openSearchOptions(user);
    await user.type(screen.getByLabelText('Latitude'), '38.42');
    await user.type(screen.getByLabelText('Longitude'), '27.14');
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));

    expect(await screen.findByText('Stub Address 7')).toBeInTheDocument();
    expect(facilitiesHits).toBe(0);
    expect(screen.queryByTestId('municipal-facility-results')).not.toBeInTheDocument();
    expect(screen.getByTestId('stub-municipal-count')).toHaveTextContent('0');
  });

  it('loads municipal facilities as a separate inventory when enabled', async () => {
    const facility = makeMunicipalFacility({
      id: 'fac-map-1',
      latitude: 38.42,
      longitude: 27.14,
      displayName: 'Konak Municipal Lot',
    });

    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([facility])),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await openSearchOptions(user);
    await user.type(screen.getByLabelText('Latitude'), '38.42');
    await user.type(screen.getByLabelText('Longitude'), '27.14');
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));

    expect(await screen.findByTestId('municipal-facility-results')).toBeInTheDocument();
    expect(screen.getByText('Municipal parking facilities')).toBeInTheDocument();
    await expectMunicipalFacilityLoaded('Konak Municipal Lot', '1');
    expect(await screen.findByText('Stub Address 7')).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: 'stub-select-first-facility' }));
    expect(await screen.findByTestId('selected-municipal-facility-preview')).toBeInTheDocument();
    expect(screen.getByText('Municipal parking')).toBeInTheDocument();
  });

  it('shows municipal empty state without hiding community spots', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([])),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await openSearchOptions(user);
    await user.type(screen.getByLabelText('Latitude'), '38.42');
    await user.type(screen.getByLabelText('Longitude'), '27.14');
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));

    expect(await screen.findByTestId('municipal-facility-empty')).toBeInTheDocument();
    expect(screen.getByText('Stub Address 7')).toBeInTheDocument();
  });

  it('shows municipal error state while community spots still render', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () =>
        HttpResponse.json(apiErrorBody('INTERNAL', 'fail'), { status: 500 }),
      ),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await openSearchOptions(user);
    await user.type(screen.getByLabelText('Latitude'), '38.42');
    await user.type(screen.getByLabelText('Longitude'), '27.14');
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));

    expect(await screen.findByTestId('municipal-facility-error')).toBeInTheDocument();
    expect(screen.getByText('Stub Address 7')).toBeInTheDocument();
  });

  it('filters municipal markers and sidebar client-side without extra facilities requests', async () => {
    let facilitiesHits = 0;
    const izum = makeMunicipalFacility({
      id: 'fac-izum',
      displayName: 'IZUM Lot',
      sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
      availableSpaces: 5,
      facilityType: 'OFF_STREET',
      latitude: 38.42,
      longitude: 27.14,
    });
    const osm = makeMunicipalFacility({
      id: 'fac-osm',
      displayName: 'OSM Lot',
      sourceLabel: 'OpenStreetMap contributors / Geofabrik GmbH',
      availableSpaces: null,
      facilityType: 'UNKNOWN',
      latitude: 38.421,
      longitude: 27.141,
    });

    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => {
        facilitiesHits += 1;
        return HttpResponse.json([izum, osm]);
      }),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await openSearchOptions(user);
    await user.type(screen.getByLabelText('Latitude'), '38.42');
    await user.type(screen.getByLabelText('Longitude'), '27.14');
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));

    expect(await screen.findByText('IZUM Lot')).toBeInTheDocument();
    expect(screen.getByText('OSM Lot')).toBeInTheDocument();
    expect(screen.getByTestId('stub-municipal-count')).toHaveTextContent('2');
    expect(facilitiesHits).toBe(1);

    await user.click(screen.getByTestId('municipal-filter-availability-available'));
    expect(screen.getByText('IZUM Lot')).toBeInTheDocument();
    expect(screen.queryByText('OSM Lot')).not.toBeInTheDocument();
    expect(screen.getByTestId('stub-municipal-count')).toHaveTextContent('1');
    expect(screen.getByText('1 of 2 facilities')).toBeInTheDocument();
    expect(facilitiesHits).toBe(1);

    await user.click(screen.getByTestId('municipal-filter-clear'));
    expect(screen.getByText('OSM Lot')).toBeInTheDocument();
    expect(screen.getByTestId('stub-municipal-count')).toHaveTextContent('2');
    expect(facilitiesHits).toBe(1);
  });

  it('clears the selected municipal preview when filters hide that facility', async () => {
    const available = makeMunicipalFacility({
      id: 'fac-available',
      displayName: 'Available Lot',
      availableSpaces: 4,
      latitude: 38.42,
      longitude: 27.14,
    });
    const unavailable = makeMunicipalFacility({
      id: 'fac-hidden',
      displayName: 'Unavailable Lot',
      availableSpaces: 0,
      latitude: 38.421,
      longitude: 27.141,
    });

    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () =>
        HttpResponse.json([available, unavailable]),
      ),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await openSearchOptions(user);
    await user.type(screen.getByLabelText('Latitude'), '38.42');
    await user.type(screen.getByLabelText('Longitude'), '27.14');
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));

    await user.click(await screen.findByRole('button', { name: 'stub-select-first-facility' }));
    expect(await screen.findByTestId('selected-municipal-facility-preview')).toBeInTheDocument();

    await user.click(screen.getByTestId('municipal-filter-availability-unavailable'));
    expect(screen.queryByTestId('selected-municipal-facility-preview')).not.toBeInTheDocument();
  });
});

describe('MapPage dual-inventory layer visibility (WEB-MUNI-05)', () => {
  beforeEach(() => {
    runtime = createTestAppRuntime();
    signInAs(runtime, ['USER']);
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
    server.use(
      http.get(`${API_BASE}/users/me/vehicle`, () =>
        HttpResponse.json({ vehicleType: 'SEDAN', plate: '35PK123' }),
      ),
    );
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => new HttpResponse(null, { status: 204 })),
    );
    server.use(
      http.get(`${API_BASE}/parking/sessions/lifecycle-config`, () =>
        HttpResponse.json({
          confirmAfterMs: 43_200_000,
          reminderLeadMs: 3_600_000,
          autoCompleteAfterMs: 86_400_000,
        }),
      ),
    );
    stubGeolocation(undefined);
  });

  afterEach(() => {
    clearUserSessionQueries(runtime.queryClient);
    resetAuth(runtime);
  });

  async function searchNear(user: ReturnType<typeof userEvent.setup>) {
    await openSearchOptions(user);
    await user.type(screen.getByLabelText('Latitude'), '38.42');
    await user.type(screen.getByLabelText('Longitude'), '27.14');
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));
  }

  it('defaults both layers visible and omits controls when discovery flag is off', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([])),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled={false} />);
    const user = userEvent.setup();
    await searchNear(user);

    expect(await screen.findByText('Stub Address 7')).toBeInTheDocument();
    expect(screen.queryByTestId('map-layer-visibility-controls')).not.toBeInTheDocument();
    expect(screen.getByTestId('stub-spot-count')).toHaveTextContent('1');
  });

  it('toggles community and municipal layers independently without refetch', async () => {
    let spotsHits = 0;
    let facilitiesHits = 0;
    const facility = makeMunicipalFacility({
      id: 'fac-layer-1',
      displayName: 'Layer Lot',
      latitude: 38.42,
      longitude: 27.14,
      availableSpaces: 3,
    });

    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => {
        spotsHits += 1;
        return HttpResponse.json([spot]);
      }),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => {
        facilitiesHits += 1;
        return HttpResponse.json([facility]);
      }),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);

    expect(await screen.findByTestId('map-layer-visibility-controls')).toBeInTheDocument();
    expect(screen.getByTestId('map-layer-community')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('map-layer-municipal')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Layer Lot')).toBeInTheDocument();
    expect(screen.getByText('Stub Address 7')).toBeInTheDocument();
    expect(screen.getByTestId('stub-spot-count')).toHaveTextContent('1');
    expect(screen.getByTestId('stub-municipal-count')).toHaveTextContent('1');
    expect(spotsHits).toBe(1);
    expect(facilitiesHits).toBe(1);

    await user.click(screen.getByTestId('map-layer-municipal'));
    expect(screen.getByTestId('map-layer-municipal')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByTestId('municipal-facility-results')).not.toBeInTheDocument();
    expect(screen.queryByText('Layer Lot')).not.toBeInTheDocument();
    expect(screen.getByText('Stub Address 7')).toBeInTheDocument();
    expect(screen.getByTestId('stub-municipal-count')).toHaveTextContent('0');
    expect(screen.getByTestId('stub-spot-count')).toHaveTextContent('1');
    expect(spotsHits).toBe(1);
    expect(facilitiesHits).toBe(1);

    await user.click(screen.getByTestId('map-layer-community'));
    expect(screen.getByTestId('map-layer-community')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByText('Stub Address 7')).not.toBeInTheDocument();
    expect(await screen.findByTestId('map-layers-both-hidden')).toBeInTheDocument();
    expect(screen.getByTestId('map-layers-both-hidden')).toHaveTextContent(
      /No map layers are currently visible/i,
    );
    expect(screen.getByTestId('stub-spot-count')).toHaveTextContent('0');
    expect(screen.getByTestId('stub-municipal-count')).toHaveTextContent('0');
    expect(spotsHits).toBe(1);
    expect(facilitiesHits).toBe(1);

    await user.click(screen.getByTestId('map-layer-municipal'));
    expect(screen.queryByTestId('map-layers-both-hidden')).not.toBeInTheDocument();
    expect(screen.getByText('Layer Lot')).toBeInTheDocument();
    expect(screen.getByTestId('stub-municipal-count')).toHaveTextContent('1');
    expect(spotsHits).toBe(1);
    expect(facilitiesHits).toBe(1);
  });

  it('preserves municipal filters while the municipal layer is hidden', async () => {
    const izum = makeMunicipalFacility({
      id: 'fac-izum-layer',
      displayName: 'IZUM Visible',
      sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
      availableSpaces: 5,
      facilityType: 'OFF_STREET',
      latitude: 38.42,
      longitude: 27.14,
    });
    const osm = makeMunicipalFacility({
      id: 'fac-osm-layer',
      displayName: 'OSM Hidden By Filter',
      sourceLabel: 'OpenStreetMap contributors / Geofabrik GmbH',
      availableSpaces: null,
      facilityType: 'UNKNOWN',
      latitude: 38.421,
      longitude: 27.141,
    });

    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([izum, osm])),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);

    expect(await screen.findByText('IZUM Visible')).toBeInTheDocument();
    await user.click(screen.getByTestId('municipal-filter-availability-available'));
    expect(screen.queryByText('OSM Hidden By Filter')).not.toBeInTheDocument();
    expect(screen.getByTestId('stub-municipal-count')).toHaveTextContent('1');

    await user.click(screen.getByTestId('map-layer-municipal'));
    expect(screen.queryByTestId('municipal-facility-results')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('map-layer-municipal'));
    expect(await screen.findByText('IZUM Visible')).toBeInTheDocument();
    expect(screen.queryByText('OSM Hidden By Filter')).not.toBeInTheDocument();
    expect(screen.getByTestId('stub-municipal-count')).toHaveTextContent('1');
  });

  it('clears only the selected inventory when its layer is hidden', async () => {
    const facility = makeMunicipalFacility({
      id: 'fac-sel',
      displayName: 'Select Lot',
      latitude: 38.42,
      longitude: 27.14,
    });
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([facility])),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);

    expect(await screen.findByText('Select Lot')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'stub-select-first-facility' }));
    expect(await screen.findByTestId('selected-municipal-facility-preview')).toBeInTheDocument();

    await user.click(screen.getByTestId('map-layer-community'));
    expect(screen.getByTestId('selected-municipal-facility-preview')).toBeInTheDocument();

    await user.click(screen.getByTestId('map-layer-municipal'));
    expect(screen.queryByTestId('selected-municipal-facility-preview')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('map-layer-community'));
    await user.click(screen.getByTestId('map-layer-municipal'));
    expect(screen.queryByTestId('selected-municipal-facility-preview')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'stub-select-first-spot' }));
    expect(await screen.findByTestId('selected-spot-preview')).toBeInTheDocument();
    await user.click(screen.getByTestId('map-layer-community'));
    expect(screen.queryByTestId('selected-spot-preview')).not.toBeInTheDocument();
  });

  it('supports keyboard activation of layer toggles', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () =>
        HttpResponse.json([makeMunicipalFacility({ latitude: 38.42, longitude: 27.14 })]),
      ),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);

    const municipalToggle = await screen.findByTestId('map-layer-municipal');
    municipalToggle.focus();
    expect(municipalToggle).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(municipalToggle).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByTestId('municipal-facility-results')).not.toBeInTheDocument();
  });

  it('keeps focus on the activated layer toggle when hiding a selected municipal result', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () =>
        HttpResponse.json([
          makeMunicipalFacility({
            id: 'fac-focus',
            displayName: 'Focus Lot',
            latitude: 38.42,
            longitude: 27.14,
          }),
        ]),
      ),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);

    await user.click(await screen.findByRole('button', { name: 'stub-select-first-facility' }));
    expect(await screen.findByTestId('selected-municipal-facility-preview')).toBeInTheDocument();

    const municipalToggle = screen.getByTestId('map-layer-municipal');
    municipalToggle.focus();
    await user.keyboard('{Enter}');

    expect(municipalToggle).toHaveFocus();
    expect(screen.queryByTestId('selected-municipal-facility-preview')).not.toBeInTheDocument();
  });

  it('announces marker-driven selection without repeating the same message for list selection', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () =>
        HttpResponse.json([
          makeMunicipalFacility({
            id: 'fac-announce',
            displayName: 'Announce Lot',
            latitude: 38.42,
            longitude: 27.14,
          }),
        ]),
      ),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);

    expect(screen.getByRole('region', { name: 'Interactive parking discovery map' })).toBeInTheDocument();
    expect(screen.getByTestId('stub-map-selection')).toHaveTextContent('');

    await user.click(await screen.findByRole('button', { name: 'stub-select-first-facility' }));
    expect(screen.getByTestId('stub-map-selection')).toHaveTextContent(
      'Selected municipal facility: Announce Lot',
    );

    await user.click(screen.getByTestId('municipal-facility-result'));
    expect(screen.getByTestId('stub-map-selection')).toHaveTextContent('');
  });

  it('hydrates municipal filters and layer visibility from URL params', async () => {
    let spotsHits = 0;
    let facilitiesHits = 0;
    const izum = makeMunicipalFacility({
      id: 'fac-url-izum',
      displayName: 'URL Visible',
      sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
      availableSpaces: 5,
      facilityType: 'OFF_STREET',
      latitude: 38.42,
      longitude: 27.14,
    });
    const osm = makeMunicipalFacility({
      id: 'fac-url-osm',
      displayName: 'URL Hidden',
      sourceLabel: 'OpenStreetMap contributors / Geofabrik GmbH',
      availableSpaces: null,
      facilityType: 'UNKNOWN',
      latitude: 38.421,
      longitude: 27.141,
    });

    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => {
        spotsHits += 1;
        return HttpResponse.json([spot]);
      }),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => {
        facilitiesHits += 1;
        return HttpResponse.json([izum, osm]);
      }),
    );

    const { router } = renderWithProviders(<MapPage municipalDiscoveryEnabled />, {
      initialEntries: [
        '/map?communityLayer=0&municipalAvailability=available&municipalSources=Izmir%20Buyuksehir%20Belediyesi%20%2F%20IZUM',
      ],
    });
    const user = userEvent.setup();
    await searchNear(user);

    expect(await screen.findByTestId('map-layer-visibility-controls')).toBeInTheDocument();
    expect(screen.getByTestId('map-layer-community')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('map-layer-municipal')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText('Stub Address 7')).not.toBeInTheDocument();
    expect(screen.getByText('URL Visible')).toBeInTheDocument();
    expect(screen.queryByText('URL Hidden')).not.toBeInTheDocument();
    expect(screen.getByTestId('stub-spot-count')).toHaveTextContent('0');
    expect(screen.getByTestId('stub-municipal-count')).toHaveTextContent('1');
    expect(router.state.location.search).toContain('communityLayer=0');
    expect(router.state.location.search).toContain('municipalAvailability=available');
    expect(spotsHits).toBe(1);
    expect(facilitiesHits).toBe(1);
  });

  it('restores filter and layer state from browser history without refetch', async () => {
    let spotsHits = 0;
    let facilitiesHits = 0;
    const availableStreet = makeMunicipalFacility({
      id: 'fac-history-1',
      displayName: 'History Match',
      sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
      availableSpaces: 2,
      facilityType: 'ON_STREET',
      latitude: 38.42,
      longitude: 27.14,
    });
    const unavailableLot = makeMunicipalFacility({
      id: 'fac-history-2',
      displayName: 'History Hidden',
      sourceLabel: 'OpenStreetMap contributors / Geofabrik GmbH',
      availableSpaces: 0,
      facilityType: 'OFF_STREET',
      latitude: 38.421,
      longitude: 27.141,
    });

    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => {
        spotsHits += 1;
        return HttpResponse.json([spot]);
      }),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => {
        facilitiesHits += 1;
        return HttpResponse.json([availableStreet, unavailableLot]);
      }),
    );

    const { router } = renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);

    expect(await screen.findByText('History Match')).toBeInTheDocument();
    await user.click(screen.getByTestId('municipal-filter-availability-available'));
    await user.click(screen.getByTestId('map-layer-community'));

    await waitFor(() => {
      expect(router.state.location.search).toContain('communityLayer=0');
      expect(router.state.location.search).toContain('municipalAvailability=available');
    });
    expect(screen.queryByText('Stub Address 7')).not.toBeInTheDocument();
    expect(screen.getByText('History Match')).toBeInTheDocument();
    expect(screen.queryByText('History Hidden')).not.toBeInTheDocument();
    expect(spotsHits).toBe(1);
    expect(facilitiesHits).toBe(1);

    await act(async () => {
      await router.navigate(-1);
    });
    await waitFor(() => {
      expect(screen.getByTestId('map-layer-community')).toHaveAttribute('aria-pressed', 'true');
    });
    expect(screen.getByText('Stub Address 7')).toBeInTheDocument();
    expect(screen.getByText('History Match')).toBeInTheDocument();
    expect(screen.queryByText('History Hidden')).not.toBeInTheDocument();

    await act(async () => {
      await router.navigate(-1);
    });
    await waitFor(() => {
      expect(screen.getByTestId('map-layer-community')).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByTestId('municipal-filter-availability-available')).toHaveAttribute(
        'aria-pressed',
        'false',
      );
    });
    expect(screen.getByText('Stub Address 7')).toBeInTheDocument();
    expect(screen.getByText('History Match')).toBeInTheDocument();
    expect(screen.getByText('History Hidden')).toBeInTheDocument();
    expect(spotsHits).toBe(1);
    expect(facilitiesHits).toBe(1);
  });

  it('renders Turkish layer labels', async () => {
    const { withLocale } = await import('@/test/utils');
    await withLocale('tr');
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([])),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Filtreler ve arama seçenekleri' }));
    await user.type(screen.getByLabelText('Enlem'), '38.42');
    await user.type(screen.getByLabelText('Boylam'), '27.14');
    await user.click(screen.getByRole('button', { name: 'Yakında ara' }));

    expect(await screen.findByText('Harita katmanları')).toBeInTheDocument();
    expect(screen.getByTestId('map-layer-community')).toHaveTextContent('Topluluk park yerleri');
    expect(screen.getByTestId('map-layer-municipal')).toHaveTextContent('Belediye tesisleri');
  });
});

describe('MapPage dual-inventory empty chrome (WEB-MUNI-06)', () => {
  beforeEach(() => {
    runtime = createTestAppRuntime();
    signInAs(runtime, ['USER']);
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
    server.use(
      http.get(`${API_BASE}/users/me/vehicle`, () =>
        HttpResponse.json({ vehicleType: 'SEDAN', plate: '35PK123' }),
      ),
    );
    server.use(
      http.get(`${API_BASE}/parking/sessions/active`, () => new HttpResponse(null, { status: 204 })),
    );
    server.use(
      http.get(`${API_BASE}/parking/sessions/lifecycle-config`, () =>
        HttpResponse.json({
          confirmAfterMs: 43_200_000,
          reminderLeadMs: 3_600_000,
          autoCompleteAfterMs: 86_400_000,
        }),
      ),
    );
    stubGeolocation(undefined);
  });

  afterEach(() => {
    clearUserSessionQueries(runtime.queryClient);
    resetAuth(runtime);
    restoreGeolocation();
  });

  async function searchNear(user: ReturnType<typeof userEvent.setup>) {
    await openSearchOptions(user);
    await user.type(screen.getByLabelText('Latitude'), '38.42');
    await user.type(screen.getByLabelText('Longitude'), '27.14');
    await user.click(screen.getByRole('button', { name: 'Search nearby' }));
  }

  it('municipal-only results do not show community-only nothing-nearby chrome', async () => {
    let spotsHits = 0;
    let facilitiesHits = 0;
    const facility = makeMunicipalFacility({
      id: 'fac-empty-1',
      displayName: 'Chrome Lot',
      latitude: 38.42,
      longitude: 27.14,
    });
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => {
        spotsHits += 1;
        return HttpResponse.json([]);
      }),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => {
        facilitiesHits += 1;
        return HttpResponse.json([facility]);
      }),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);

    expect(await screen.findByText('Chrome Lot')).toBeInTheDocument();
    expect(screen.getByTestId('map-sheet-summary')).toHaveTextContent(/municipal facility/i);
    expect(screen.getByTestId('map-sheet-summary')).not.toHaveTextContent('No spots nearby');
    expect(screen.getByText('No community spots nearby')).toBeInTheDocument();
    expect(screen.queryByText('No spots nearby')).not.toBeInTheDocument();

    await openSearchOptions(user);
    expect(screen.getByTestId('map-sheet-show-results')).toHaveTextContent(/municipal facility/i);
    await user.click(screen.getByTestId('map-sheet-show-results'));
    expect(screen.getByTestId('municipal-facility-results')).toBeInTheDocument();
    expect(spotsHits).toBe(1);
    expect(facilitiesHits).toBe(1);
  });

  it('community-only results keep legacy community summary', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([])),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);

    expect(await screen.findByText('Stub Address 7')).toBeInTheDocument();
    expect(screen.getByTestId('map-sheet-summary')).toHaveTextContent(/spot nearby/i);
    await openSearchOptions(user);
    expect(screen.getByTestId('map-sheet-show-results')).toHaveTextContent(/result/i);
  });

  it('dual results summarize both inventories separately', async () => {
    const facility = makeMunicipalFacility({
      id: 'fac-dual-1',
      displayName: 'Dual Lot',
      latitude: 38.42,
      longitude: 27.14,
    });
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([spot])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([facility])),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);

    expect(await screen.findByText('Stub Address 7')).toBeInTheDocument();
    expect(await screen.findByText('Dual Lot')).toBeInTheDocument();
    expect(screen.getByTestId('map-sheet-summary')).toHaveTextContent(/community spots/i);
    expect(screen.getByTestId('map-sheet-summary')).toHaveTextContent(/municipal facilities/i);
    await openSearchOptions(user);
    expect(screen.getByTestId('map-sheet-show-results')).toHaveTextContent(/community spots and/i);
  });

  it('true empty differs from both-hidden', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([])),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);

    await expectSettledMapSheetSummary('No visible results nearby');
    expect(screen.queryByTestId('map-layers-both-hidden')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('map-layer-community'));
    await user.click(screen.getByTestId('map-layer-municipal'));
    expect(screen.getByTestId('map-layers-both-hidden')).toBeInTheDocument();
    expect(screen.getByTestId('map-sheet-summary')).toHaveTextContent(
      'No map layers are currently visible',
    );
    await openSearchOptions(user);
    expect(screen.getByTestId('map-sheet-results-hint')).toHaveTextContent(/Turn on Community/i);
  });

  it('municipal filtered-empty summary differs from true empty', async () => {
    let facilitiesHits = 0;
    const facility = makeMunicipalFacility({
      id: 'fac-filter-empty',
      displayName: 'Filter Lot',
      latitude: 38.42,
      longitude: 27.14,
      availableSpaces: 2,
    });
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => {
        facilitiesHits += 1;
        return HttpResponse.json([facility]);
      }),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);
    expect(await screen.findByText('Filter Lot')).toBeInTheDocument();

    await user.click(screen.getByTestId('municipal-filter-availability-unavailable'));
    expect(screen.getByText('No facilities match these filters')).toBeInTheDocument();
    expect(screen.getByTestId('map-sheet-summary')).toHaveTextContent(
      'No municipal facilities match filters',
    );
    await openSearchOptions(user);
    expect(screen.getByTestId('map-sheet-show-results')).toBeInTheDocument();
    expect(facilitiesHits).toBe(1);
  });

  it('partial community failure preserves municipal results chrome', async () => {
    const facility = makeMunicipalFacility({
      id: 'fac-partial',
      displayName: 'Healthy Lot',
      latitude: 38.42,
      longitude: 27.14,
    });
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () =>
        HttpResponse.json({ message: 'boom' }, { status: 500 }),
      ),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([facility])),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);

    expect(await screen.findByText('Healthy Lot')).toBeInTheDocument();
    expect(screen.getByTestId('map-sheet-summary')).toHaveTextContent(/municipal facility/i);
    expect(screen.getByTestId('map-sheet-summary')).not.toHaveTextContent("Couldn't load results");
  });

  it('layer toggle recomputes summary without refetch', async () => {
    let spotsHits = 0;
    let facilitiesHits = 0;
    const facility = makeMunicipalFacility({
      id: 'fac-toggle-chrome',
      displayName: 'Toggle Lot',
      latitude: 38.42,
      longitude: 27.14,
    });
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => {
        spotsHits += 1;
        return HttpResponse.json([]);
      }),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => {
        facilitiesHits += 1;
        return HttpResponse.json([facility]);
      }),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await searchNear(user);
    expect(await screen.findByText('Toggle Lot')).toBeInTheDocument();
    expect(spotsHits).toBe(1);
    expect(facilitiesHits).toBe(1);

    await user.click(screen.getByTestId('map-layer-municipal'));
    expect(screen.getByTestId('map-sheet-summary')).toHaveTextContent('No visible results nearby');
    await user.click(screen.getByTestId('map-layer-municipal'));
    expect(screen.getByTestId('map-sheet-summary')).toHaveTextContent(/municipal facility/i);
    expect(spotsHits).toBe(1);
    expect(facilitiesHits).toBe(1);
  });

  it('flag-off UI keeps community-only empty copy', async () => {
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([])),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled={false} />);
    const user = userEvent.setup();
    await searchNear(user);

    expect(await screen.findByTestId('map-sheet-summary')).toHaveTextContent('No spots nearby');
    expect(screen.getByRole('heading', { name: 'No spots nearby' })).toBeInTheDocument();
    expect(screen.queryByText('No community spots nearby')).not.toBeInTheDocument();
    expect(screen.queryByText(/municipal facility/i)).not.toBeInTheDocument();
  });

  it('renders Turkish municipal-only summary', async () => {
    const { withLocale } = await import('@/test/utils');
    await withLocale('tr');
    const facility = makeMunicipalFacility({
      id: 'fac-tr-chrome',
      displayName: 'TR Lot',
      latitude: 38.42,
      longitude: 27.14,
    });
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([facility])),
    );

    renderWithProviders(<MapPage municipalDiscoveryEnabled />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Filtreler ve arama seçenekleri' }));
    await user.type(screen.getByLabelText('Enlem'), '38.42');
    await user.type(screen.getByLabelText('Boylam'), '27.14');
    await user.click(screen.getByRole('button', { name: 'Yakında ara' }));

    expect(await screen.findByText('TR Lot')).toBeInTheDocument();
    expect(screen.getByTestId('map-sheet-summary')).toHaveTextContent(/belediye tesisi/i);
    expect(screen.getByTestId('map-sheet-summary')).not.toHaveTextContent('Yakında park yeri yok');
  });

  it('preserves smartReturn=1 while syncing municipal URL state', async () => {
    const facility = makeMunicipalFacility({
      id: 'fac-smart-return-url',
      displayName: 'Smart Return Lot',
      latitude: 38.4237,
      longitude: 27.1428,
      availableSpaces: 4,
    });
    server.use(
      http.get(`${API_BASE}/users/me/smart-return`, () => HttpResponse.json(smartReturnSettings)),
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([facility])),
    );

    const { router } = renderWithProviders(<MapPage municipalDiscoveryEnabled />, {
      initialEntries: ['/map?smartReturn=1&communityLayer=0&municipalAvailability=available'],
    });

    expect(await screen.findByText('Showing parking near your saved home.')).toBeInTheDocument();
    expect(await screen.findByText('Smart Return Lot')).toBeInTheDocument();
    expect(screen.getByTestId('map-layer-community')).toHaveAttribute('aria-pressed', 'false');
    expect(router.state.location.search).toContain('smartReturn=1');
    expect(router.state.location.search).toContain('communityLayer=0');
    expect(router.state.location.search).toContain('municipalAvailability=available');
  });

  it('drops invalid stale municipal URL params safely after data loads', async () => {
    const facility = makeMunicipalFacility({
      id: 'fac-stale-url',
      displayName: 'Stale Safe Lot',
      latitude: 38.42,
      longitude: 27.14,
      availableSpaces: 3,
      sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
      facilityType: 'OFF_STREET',
    });
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([facility])),
    );

    const { router } = renderWithProviders(<MapPage municipalDiscoveryEnabled />, {
      initialEntries: [
        '/map?municipalAvailability=nope&municipalSources=Missing%20Source&municipalTypes=NOT_REAL&municipalProvenance=2',
      ],
    });
    const user = userEvent.setup();
    await searchNear(user);

    expect(await screen.findByText('Stale Safe Lot')).toBeInTheDocument();
    expect(screen.queryByText('No facilities match these filters')).not.toBeInTheDocument();
    await waitFor(() => {
      expect(router.state.location.search).toBe('');
    });
  });

  it('preserves unrelated params while syncing municipal URL state', async () => {
    const facility = makeMunicipalFacility({
      id: 'fac-unrelated-url',
      displayName: 'Related Lot',
      latitude: 38.4237,
      longitude: 27.1428,
      availableSpaces: 4,
    });
    server.use(
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([facility])),
    );

    const { router } = renderWithProviders(<MapPage municipalDiscoveryEnabled />, {
      initialEntries: ['/map?foo=bar&smartReturn=1'],
    });
    const user = userEvent.setup();
    await searchNear(user);

    await user.click(screen.getByTestId('map-layer-community'));

    await waitFor(() => {
      expect(router.state.location.search).toContain('foo=bar');
      expect(router.state.location.search).toContain('smartReturn=1');
      expect(router.state.location.search).toContain('communityLayer=0');
    });
  });

  it('flag-off canonicalization strips municipal URL state but preserves smartReturn', async () => {
    server.use(
      http.get(`${API_BASE}/users/me/smart-return`, () => HttpResponse.json(smartReturnSettings)),
      http.get(`${API_BASE}/parking/spots/nearby`, () => HttpResponse.json([])),
      http.get(`${API_BASE}/parking/facilities/nearby`, () => HttpResponse.json([])),
    );

    const { router } = renderWithProviders(<MapPage municipalDiscoveryEnabled={false} />, {
      initialEntries: [
        '/map?smartReturn=1&communityLayer=0&municipalLayer=0&municipalAvailability=available&municipalSources=IZUM',
      ],
    });

    expect(await screen.findByText('Showing parking near your saved home.')).toBeInTheDocument();
    await waitFor(() => {
      expect(router.state.location.search).toBe('?smartReturn=1');
    });
    expect(screen.queryByTestId('map-layer-visibility-controls')).not.toBeInTheDocument();
    expect(screen.queryByTestId('municipal-facility-filters')).not.toBeInTheDocument();
  });
});
