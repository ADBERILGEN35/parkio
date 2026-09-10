import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders } from '@/test/utils';
import { PublicExplorePage } from './PublicExplorePage';

vi.mock('@/config/env', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/config/env')>();
  return {
    ...actual,
    frontendConfig: {
      ...actual.frontendConfig,
      features: { ...actual.frontendConfig.features, publicExplore: true },
      registrationModeBootstrap: 'CLOSED',
    },
  };
});

vi.mock('@/components/map/NearbySpotsMap', () => ({
  NearbySpotsMap: ({
    municipalFacilities,
    onSelectMunicipalFacility,
    onLocate,
    locating,
    spots,
    destinationMarker,
  }: {
    municipalFacilities: Array<{ id: string; displayName: string | null }>;
    onSelectMunicipalFacility?: (id: string | null) => void;
    onLocate?: () => void;
    locating?: boolean;
    spots: unknown[];
    destinationMarker?: { latitude: number; longitude: number; label: string } | null;
  }) => (
    <div aria-label="Parkio public parking map" data-testid="public-explore-map">
      <button
        type="button"
        data-testid="map-floating-locate"
        aria-label="Use my location"
        disabled={locating}
        onClick={() => onLocate?.()}
      >
        Locate
      </button>
      <span data-testid="community-spot-marker-count">{spots.length}</span>
      {destinationMarker ? (
        <div data-testid="destination-marker">{destinationMarker.label}</div>
      ) : null}
      {municipalFacilities.map((facility) => (
        <button
          key={facility.id}
          type="button"
          data-testid="municipal-facility-marker"
          onClick={() => onSelectMunicipalFacility?.(facility.id)}
        >
          {facility.displayName}
        </button>
      ))}
    </div>
  ),
}));

const facility = {
  id: '00000000-0000-0000-0000-000000000901',
  displayName: 'Konak Katli Otoparki',
  operatorName: 'IZELMAN A.S.',
  facilityType: 'OFF_STREET',
  addressText: 'Konak, Izmir',
  latitude: 38.4237,
  longitude: 27.1428,
  capacityTotal: 100,
  availableSpaces: 42,
  availabilityFreshness: 'LIVE',
  dataUpdatedAt: '2026-09-04T10:00:00Z',
  sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
  attribution: 'Includes public sector information licensed under CC BY 4.0.',
};

const farFacility = {
  ...facility,
  id: '00000000-0000-0000-0000-000000000902',
  displayName: 'Alsancak Liman Otoparki',
  latitude: 38.45,
  longitude: 27.2,
};

function discoveryResponse(overrides: Record<string, unknown> = {}) {
  return {
    facilities: [facility],
    municipalTotalInScope: 1,
    municipalHiddenCount: 0,
    communitySpotCountInScope: null,
    ...overrides,
  };
}

function grantedPosition(lat: number, lng: number): GeolocationPosition {
  return {
    coords: {
      latitude: lat,
      longitude: lng,
      accuracy: 10,
      altitude: null,
      altitudeAccuracy: null,
      heading: null,
      speed: null,
      toJSON() {
        return this;
      },
    },
    timestamp: Date.now(),
    toJSON() {
      return this;
    },
  } as GeolocationPosition;
}

describe('PublicExplorePage', () => {
  const getCurrentPosition = vi.fn();

  beforeEach(() => {
    getCurrentPosition.mockReset();
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition },
    });
    server.use(
      http.get(`${API_BASE}/auth/registration-mode`, () =>
        HttpResponse.json({ mode: 'CLOSED' }),
      ),
    );
  });

  it('shows contribution CTA that opens AuthGate without private route fetches', async () => {
    const privateUpload = vi.fn();
    const privateNearby = vi.fn();
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () =>
        HttpResponse.json(discoveryResponse()),
      ),
      http.get(`${API_BASE}/parking/spots/nearby`, () => {
        privateNearby();
        return HttpResponse.json([]);
      }),
      http.get(`${API_BASE}/media/upload*`, () => {
        privateUpload();
        return HttpResponse.json({});
      }),
      http.post(`${API_BASE}/parking/spots`, () => {
        privateUpload();
        return HttpResponse.json({});
      }),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    expect(await screen.findByTestId('public-explore-contribute-cta')).toHaveTextContent(
      'Report a parking spot',
    );
    await userEvent.click(screen.getByTestId('public-explore-contribute-cta'));
    expect(await screen.findByTestId('auth-gate-dialog')).toHaveAttribute(
      'data-auth-gate-intent',
      'contribute',
    );
    expect(screen.getByTestId('auth-gate-dialog')).toHaveTextContent('Report a parking spot');
    expect(screen.getByTestId('auth-gate-register')).toHaveTextContent('About registration');
    expect(screen.queryByText(/^Sign up$/)).not.toBeInTheDocument();
    expect(privateNearby).not.toHaveBeenCalled();
    expect(privateUpload).not.toHaveBeenCalled();
    expect(screen.getByTestId('community-spot-marker-count')).toHaveTextContent('0');
  });

  it('counts only renderable municipal markers and never shows a mismatched visible summary', async () => {
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () =>
        HttpResponse.json(
          discoveryResponse({
            facilities: [
              facility,
              farFacility,
              {
                ...facility,
                id: '00000000-0000-0000-0000-000000000903',
                displayName: 'Broken coords',
                latitude: Number.NaN,
                longitude: 27.1,
              },
            ],
            municipalTotalInScope: 9,
            municipalHiddenCount: 5,
          }),
        ),
      ),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    expect(await screen.findByTestId('public-explore-discovery-summary')).toHaveTextContent(
      '2 parking facilities',
    );
    expect(screen.getByTestId('municipal-hidden-teaser')).toHaveTextContent('+5 more');
    expect(screen.getAllByTestId('municipal-facility-marker')).toHaveLength(2);
  });

  it('renders the canonical product map and gates full detail behind AuthGate', async () => {
    const listCalls = vi.fn();
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, ({ request }) => {
        listCalls(new URL(request.url).searchParams);
        return HttpResponse.json(discoveryResponse());
      }),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });

    expect(await screen.findByTestId('public-explore-product')).toBeInTheDocument();
    expect(await screen.findByTestId('map-floating-locate')).toBeInTheDocument();
    expect(screen.queryByText('Read-only')).not.toBeInTheDocument();
    expect(screen.queryByText('Salt okunur')).not.toBeInTheDocument();
    expect(screen.queryByText(/salt okunur/i)).not.toBeInTheDocument();
    expect(screen.queryByTestId('public-explore-locate')).not.toBeInTheDocument();
    expect(screen.getAllByLabelText('Use my location')).toHaveLength(1);
    expect(getCurrentPosition).not.toHaveBeenCalled();

    await waitFor(() => expect(listCalls).toHaveBeenCalled());
    const params = listCalls.mock.calls[0]![0] as URLSearchParams;
    expect(params.get('limit')).toBe('6');
    expect(params.get('radiusMeters')).toBe('5000');
    expect(params.get('lat')).toBeTruthy();
    expect(params.get('lng')).toBeTruthy();

    await userEvent.click(await screen.findByRole('button', { name: facility.displayName }));
    const preview = await screen.findByTestId('selected-municipal-facility-preview');
    expect(preview).toHaveTextContent(facility.displayName);
    expect(preview).not.toHaveTextContent(/km|m\b/i);
    expect(screen.queryByTestId('auth-gate-dialog')).not.toBeInTheDocument();

    await userEvent.click(screen.getByTestId('municipal-facility-view-details'));
    expect(await screen.findByTestId('auth-gate-dialog')).toBeInTheDocument();
    expect(screen.getByTestId('auth-gate-dialog')).toHaveAttribute(
      'data-auth-gate-intent',
      'facilityDetail',
    );
    expect(screen.getByTestId('auth-gate-register')).toHaveTextContent('About registration');
    expect(screen.queryByText('Sign up')).not.toBeInTheDocument();
    expect(screen.getByTestId('auth-gate-registration-support')).toHaveTextContent(
      'New account registration will open soon.',
    );
    expect(screen.queryByText(/salt okunur|read-only/i)).not.toBeInTheDocument();

    expect(screen.getByTestId('community-spot-marker-count')).toHaveTextContent('0');
  });

  it('does not show preview distance until geolocation succeeds', async () => {
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () =>
        HttpResponse.json(discoveryResponse({ facilities: [facility, farFacility] })),
      ),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    await userEvent.click(await screen.findByRole('button', { name: facility.displayName }));
    expect(await screen.findByTestId('selected-municipal-facility-preview')).not.toHaveTextContent(
      /km|m\b/i,
    );
  });

  it('keeps distance absent when location permission is denied', async () => {
    getCurrentPosition.mockImplementation((_success: PositionCallback, error?: PositionErrorCallback) => {
      error?.({
        code: 1,
        PERMISSION_DENIED: 1,
        POSITION_UNAVAILABLE: 2,
        TIMEOUT: 3,
        message: 'denied',
      } as GeolocationPositionError);
    });
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () =>
        HttpResponse.json(discoveryResponse()),
      ),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    await screen.findByTestId('public-explore-map');
    await userEvent.click(screen.getByTestId('map-floating-locate'));
    expect(await screen.findByTestId('public-explore-location-feedback')).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: facility.displayName }));
    expect(await screen.findByTestId('selected-municipal-facility-preview')).not.toHaveTextContent(
      /km|m\b/i,
    );
  });

  it('shows distance from the real user position after locate succeeds', async () => {
    getCurrentPosition.mockImplementation((success: PositionCallback) => {
      success(grantedPosition(38.45, 27.2));
    });
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () =>
        HttpResponse.json(discoveryResponse({ facilities: [facility, farFacility] })),
      ),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    await screen.findByTestId('public-explore-map');
    await userEvent.click(screen.getByTestId('map-floating-locate'));
    await waitFor(() => expect(getCurrentPosition).toHaveBeenCalled());

    await userEvent.click(await screen.findByRole('button', { name: farFacility.displayName }));
    const preview = await screen.findByTestId('selected-municipal-facility-preview');
    expect(preview).toHaveTextContent(/m|km/i);
  });

  it('requests geolocation only after the floating locate control and refetches with bounded coords', async () => {
    const listCalls = vi.fn();
    getCurrentPosition.mockImplementation((success: PositionCallback) => {
      success(grantedPosition(38.45, 27.2));
    });

    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, ({ request }) => {
        listCalls(Object.fromEntries(new URL(request.url).searchParams.entries()));
        return HttpResponse.json(discoveryResponse());
      }),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    await screen.findByTestId('public-explore-map');
    expect(getCurrentPosition).not.toHaveBeenCalled();

    await userEvent.click(screen.getByTestId('map-floating-locate'));
    await waitFor(() => expect(getCurrentPosition).toHaveBeenCalledTimes(1));
    await waitFor(() => {
      expect(listCalls.mock.calls.some((call) => call[0].lat === '38.45')).toBe(true);
    });
    const located = listCalls.mock.calls.find((call) => call[0].lat === '38.45')![0];
    expect(located.lng).toBe('27.2');
    expect(Number(located.limit)).toBeLessThanOrEqual(6);
    expect(Number(located.radiusMeters)).toBeLessThanOrEqual(5000);
  });

  it('shows municipal and community teasers and gates them without leaking hidden rows', async () => {
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () =>
        HttpResponse.json(
          discoveryResponse({
            municipalTotalInScope: 10,
            municipalHiddenCount: 7,
            communitySpotCountInScope: 12,
          }),
        ),
      ),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    expect(await screen.findByTestId('public-explore-discovery-summary')).toHaveTextContent(
      '1 parking facilities',
    );
    expect(screen.getByTestId('municipal-hidden-teaser')).toHaveTextContent('+7 more');
    expect(screen.getByTestId('community-aggregate-teaser')).toHaveTextContent(
      '12 community parking spots in this area',
    );
    expect(screen.getAllByTestId('municipal-facility-marker')).toHaveLength(1);
    expect(screen.getByTestId('community-spot-marker-count')).toHaveTextContent('0');

    await userEvent.click(screen.getByTestId('municipal-hidden-teaser'));
    expect(await screen.findByTestId('auth-gate-dialog')).toHaveAttribute(
      'data-auth-gate-intent',
      'municipalMore',
    );
    expect(screen.getByTestId('auth-gate-dialog')).toHaveTextContent('See every parking option');
    expect(screen.getByTestId('auth-gate-dialog')).not.toHaveTextContent(/nearby/i);
    await userEvent.click(screen.getByRole('button', { name: 'Not now' }));

    await userEvent.click(screen.getByTestId('community-aggregate-teaser'));
    expect(await screen.findByTestId('auth-gate-dialog')).toHaveAttribute(
      'data-auth-gate-intent',
      'community',
    );
  });

  it('suppresses community count when the privacy threshold returns null', async () => {
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () =>
        HttpResponse.json(
          discoveryResponse({
            municipalHiddenCount: 0,
            communitySpotCountInScope: null,
          }),
        ),
      ),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    await screen.findByTestId('public-explore-map');
    expect(await screen.findByTestId('public-explore-discovery-summary')).toHaveTextContent(
      '1 parking facilities',
    );
    expect(screen.queryByTestId('community-aggregate-teaser')).not.toBeInTheDocument();
    expect(screen.queryByTestId('municipal-hidden-teaser')).not.toBeInTheDocument();
    expect(screen.queryByText(/0 community/i)).not.toBeInTheDocument();
  });

  it('never substitutes fixtures when the public API is unavailable', async () => {
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () =>
        HttpResponse.json({ code: 'UNAVAILABLE' }, { status: 503 }),
      ),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });

    expect(await screen.findByText('Parking data is temporarily unavailable.')).toBeInTheDocument();
    expect(screen.queryByText(facility.displayName)).not.toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByTestId('selected-municipal-facility-preview')).not.toBeInTheDocument();
    });
  });

  it('shows destination search with certified public copy and does not call private search APIs', async () => {
    const geocodeCalls = vi.fn();
    const privateGeocode = vi.fn();
    const privatePlaces = vi.fn();
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () =>
        HttpResponse.json(discoveryResponse()),
      ),
      http.get(`${API_BASE}/public/geocoding/search`, ({ request }) => {
        geocodeCalls(Object.fromEntries(new URL(request.url).searchParams.entries()));
        return HttpResponse.json({
          results: [
            {
              id: 'dest-1',
              displayName: 'Alsancak Garı, İzmir',
              primary: 'Alsancak Garı',
              secondary: 'Alsancak, İzmir',
              lat: 38.455,
              lng: 27.15,
            },
          ],
        });
      }),
      http.get(`${API_BASE}/geocoding/search`, () => {
        privateGeocode();
        return HttpResponse.json({ results: [] });
      }),
      http.get(`${API_BASE}/places/recents/destinations`, () => {
        privatePlaces();
        return HttpResponse.json([]);
      }),
      http.get(`${API_BASE}/places/saved`, () => {
        privatePlaces();
        return HttpResponse.json([]);
      }),
      http.get(`${API_BASE}/places/favourites/destinations`, () => {
        privatePlaces();
        return HttpResponse.json([]);
      }),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    expect(await screen.findByTestId('public-explore-destination-search')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Where are you going?')).toBeInTheDocument();

    const input = screen.getByRole('combobox', { name: 'Search destination' });
    await userEvent.type(input, 'ab');
    await new Promise((resolve) => setTimeout(resolve, 450));
    expect(geocodeCalls).not.toHaveBeenCalled();

    await userEvent.type(input, 'c');
    await waitFor(() => expect(geocodeCalls).toHaveBeenCalled());
    expect(geocodeCalls.mock.calls[0]![0].q).toBe('abc');
    expect(geocodeCalls.mock.calls[0]![0].limit).toBe('5');
    expect(privateGeocode).not.toHaveBeenCalled();
    expect(privatePlaces).not.toHaveBeenCalled();
    expect(screen.queryByTestId('auth-gate-dialog')).not.toBeInTheDocument();
  });

  it('selects a destination, renders marker, rediscovers parking, and keeps distance absent without geolocation', async () => {
    const listCalls = vi.fn();
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, ({ request }) => {
        listCalls(Object.fromEntries(new URL(request.url).searchParams.entries()));
        return HttpResponse.json(
          discoveryResponse({
            facilities: [farFacility],
            municipalHiddenCount: 4,
            communitySpotCountInScope: 9,
          }),
        );
      }),
      http.get(`${API_BASE}/public/geocoding/search`, () =>
        HttpResponse.json({
          results: [
            {
              id: 'dest-alsancak',
              displayName: 'Alsancak Garı, İzmir',
              primary: 'Alsancak Garı',
              secondary: 'Alsancak, İzmir',
              lat: 38.455,
              lng: 27.15,
            },
          ],
        }),
      ),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    await screen.findByTestId('public-explore-map');
    const input = screen.getByRole('combobox', { name: 'Search destination' });
    await userEvent.click(input);
    await userEvent.type(input, 'Alsancak');
    expect(await screen.findByRole('option', { name: /Alsancak Garı/i })).toBeInTheDocument();
    await userEvent.keyboard('{ArrowDown}{Enter}');

    expect(await screen.findByTestId('destination-marker')).toHaveTextContent('Alsancak Garı');
    await waitFor(() => {
      expect(listCalls.mock.calls.some((call) => call[0].lat === '38.455')).toBe(true);
    });
    const destinationCall = listCalls.mock.calls.find((call) => call[0].lat === '38.455')![0];
    expect(destinationCall.lng).toBe('27.15');
    expect(Number(destinationCall.limit)).toBeLessThanOrEqual(6);
    expect(Number(destinationCall.radiusMeters)).toBeLessThanOrEqual(5000);

    expect(await screen.findByTestId('public-explore-discovery-summary')).toHaveTextContent(
      '+4 more',
    );
    expect(screen.getByTestId('community-aggregate-teaser')).toBeInTheDocument();
    expect(screen.getByTestId('community-spot-marker-count')).toHaveTextContent('0');

    await userEvent.click(await screen.findByRole('button', { name: farFacility.displayName }));
    expect(await screen.findByTestId('selected-municipal-facility-preview')).not.toHaveTextContent(
      /km|m\b/i,
    );
    expect(screen.queryByTestId('auth-gate-dialog')).not.toBeInTheDocument();
  });

  it('clears destination back to default origin and locate clears destination for user rediscovery', async () => {
    const listCalls = vi.fn();
    getCurrentPosition.mockImplementation((success: PositionCallback) => {
      success(grantedPosition(38.45, 27.2));
    });
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, ({ request }) => {
        listCalls(Object.fromEntries(new URL(request.url).searchParams.entries()));
        return HttpResponse.json(discoveryResponse());
      }),
      http.get(`${API_BASE}/public/geocoding/search`, () =>
        HttpResponse.json({
          results: [
            {
              id: 'dest-1',
              displayName: 'Forum Bornova',
              primary: 'Forum Bornova',
              secondary: 'Bornova, İzmir',
              lat: 38.46,
              lng: 27.22,
            },
          ],
        }),
      ),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    await screen.findByTestId('public-explore-map');
    const input = screen.getByRole('combobox', { name: 'Search destination' });
    await userEvent.click(input);
    await userEvent.type(input, 'Forum');
    expect(await screen.findByRole('option', { name: /Forum Bornova/i })).toBeInTheDocument();
    await userEvent.keyboard('{ArrowDown}{Enter}');
    expect(await screen.findByTestId('destination-marker')).toBeInTheDocument();

    await userEvent.click(screen.getByTestId('place-search-clear'));
    await waitFor(() => {
      expect(screen.queryByTestId('destination-marker')).not.toBeInTheDocument();
    });
    await waitFor(() => {
      expect(listCalls.mock.calls.some((call) => call[0].lat === '38.4237')).toBe(true);
    });

    const inputAgain = screen.getByRole('combobox', { name: 'Search destination' });
    await userEvent.click(inputAgain);
    await userEvent.type(inputAgain, 'Forum');
    expect(await screen.findByRole('option', { name: /Forum Bornova/i })).toBeInTheDocument();
    await userEvent.keyboard('{ArrowDown}{Enter}');
    expect(await screen.findByTestId('destination-marker')).toBeInTheDocument();

    await userEvent.click(screen.getByTestId('map-floating-locate'));
    await waitFor(() => expect(getCurrentPosition).toHaveBeenCalled());
    await waitFor(() => {
      expect(screen.queryByTestId('destination-marker')).not.toBeInTheDocument();
    });
    await waitFor(() => {
      expect(listCalls.mock.calls.some((call) => call[0].lat === '38.45')).toBe(true);
    });
  });

  it('keeps user-distance after geolocation when a destination is later selected', async () => {
    getCurrentPosition.mockImplementation((success: PositionCallback) => {
      success(grantedPosition(38.42, 27.14));
    });
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () =>
        HttpResponse.json(discoveryResponse({ facilities: [facility, farFacility] })),
      ),
      http.get(`${API_BASE}/public/geocoding/search`, () =>
        HttpResponse.json({
          results: [
            {
              id: 'dest-1',
              displayName: 'Alsancak',
              primary: 'Alsancak',
              secondary: 'İzmir',
              lat: 38.455,
              lng: 27.15,
            },
          ],
        }),
      ),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    await screen.findByTestId('public-explore-map');
    await userEvent.click(screen.getByTestId('map-floating-locate'));
    await waitFor(() => expect(getCurrentPosition).toHaveBeenCalled());

    const input = screen.getByRole('combobox', { name: 'Search destination' });
    await userEvent.click(input);
    await userEvent.type(input, 'Alsancak');
    expect(await screen.findByRole('option', { name: /Alsancak/i })).toBeInTheDocument();
    await userEvent.keyboard('{ArrowDown}{Enter}');
    expect(await screen.findByTestId('destination-marker')).toBeInTheDocument();

    await userEvent.click(await screen.findByRole('button', { name: farFacility.displayName }));
    expect(await screen.findByTestId('selected-municipal-facility-preview')).toHaveTextContent(
      /m|km/i,
    );
  });

  it('renders hostile suggestion labels safely and handles 429 without auth', async () => {
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () =>
        HttpResponse.json(discoveryResponse()),
      ),
      http.get(`${API_BASE}/public/geocoding/search`, () =>
        HttpResponse.json({
          results: [
            {
              id: 'xss-1',
              displayName: '<script>alert(1)</script>',
              primary: '<img src=x onerror=alert(1)>',
              secondary: '"quoted" & <b>bold</b>',
              lat: 38.42,
              lng: 27.14,
            },
          ],
        }),
      ),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });
    const input = await screen.findByRole('combobox', { name: 'Search destination' });
    await userEvent.type(input, 'Als');
    const option = await screen.findByRole('option');
    expect(option.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(option.querySelector('img')).toBeNull();
    expect(option.querySelector('script')).toBeNull();
    expect(screen.queryByTestId('auth-gate-dialog')).not.toBeInTheDocument();

    server.use(
      http.get(`${API_BASE}/public/geocoding/search`, () =>
        HttpResponse.json({ code: 'RATE_LIMITED', message: 'Too many requests' }, { status: 429 }),
      ),
    );
    await userEvent.clear(input);
    await userEvent.type(input, 'Konak');
    expect(await screen.findByText('Please wait a moment and try again.')).toBeInTheDocument();
    expect(screen.queryByText(/429|Nominatim|Rate limit/i)).not.toBeInTheDocument();
  });
});
