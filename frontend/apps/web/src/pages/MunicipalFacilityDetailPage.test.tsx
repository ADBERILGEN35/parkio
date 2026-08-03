import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MunicipalFacilityDetailPage } from './MunicipalFacilityDetailPage';
import * as openMaps from '@/components/parking/openParkingMaps';
import { makeMunicipalFacility } from '@/test/municipalFixtures';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, withLocale } from '@/test/utils';

const FACILITY_ID = '70db58f2-4cca-4010-9315-fa46b30fba1e';

const spotMapBehavior = vi.hoisted(() => ({
  failViaOnError: false,
  throwOnRender: false,
  lastProps: null as null | {
    latitude: number;
    longitude: number;
    ariaLabel?: string;
    markerPresentation?: string;
  },
  mountCount: 0,
}));

vi.mock('@/components/map/SpotMap', () => ({
  SpotMap: (props: {
    latitude: number;
    longitude: number;
    ariaLabel?: string;
    markerPresentation?: string;
    onError?: () => void;
  }) => {
    spotMapBehavior.lastProps = {
      latitude: props.latitude,
      longitude: props.longitude,
      ariaLabel: props.ariaLabel,
      markerPresentation: props.markerPresentation,
    };
    spotMapBehavior.mountCount += 1;
    if (spotMapBehavior.throwOnRender) {
      throw new Error('simulated map render failure');
    }
    if (spotMapBehavior.failViaOnError) {
      queueMicrotask(() => props.onError?.());
      return null;
    }
    return (
      <div
        data-testid="spot-map"
        data-latitude={String(props.latitude)}
        data-longitude={String(props.longitude)}
        aria-label={props.ariaLabel}
      />
    );
  },
}));

function renderDetail(
  path: string,
  options?: { municipalDiscoveryEnabled?: boolean },
) {
  return renderWithProviders(
    <Routes>
      <Route
        path="/facilities/:facilityId"
        element={
          <MunicipalFacilityDetailPage
            municipalDiscoveryEnabled={options?.municipalDiscoveryEnabled ?? true}
          />
        }
      />
      <Route path="/map" element={<div>map-page</div>} />
    </Routes>,
    { authRoles: ['USER'], initialEntries: [path] },
  );
}

function stubFacility(overrides?: Parameters<typeof makeMunicipalFacility>[0]) {
  const facility = makeMunicipalFacility({ id: FACILITY_ID, ...overrides });
  let detailHits = 0;
  let nearbyHits = 0;
  server.use(
    http.get(`${API_BASE}/parking/facilities/${FACILITY_ID}`, () => {
      detailHits += 1;
      return HttpResponse.json(facility);
    }),
    http.get(`${API_BASE}/parking/facilities/nearby`, () => {
      nearbyHits += 1;
      return HttpResponse.json([]);
    }),
  );
  return { facility, getDetailHits: () => detailHits, getNearbyHits: () => nearbyHits };
}

describe('MunicipalFacilityDetailPage (WEB-MUNI-02 / WEB-MUNI-04)', () => {
  beforeEach(() => {
    spotMapBehavior.failViaOnError = false;
    spotMapBehavior.throwOnRender = false;
    spotMapBehavior.lastProps = null;
    spotMapBehavior.mountCount = 0;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('loads facility detail from GET /parking/facilities/{id}', async () => {
    const { getDetailHits } = stubFacility({
      operatorName: 'İBB',
      capacityTotal: 200,
    });

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByTestId('municipal-facility-detail')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Konak Otopark' })).toBeInTheDocument();
    expect(screen.getByText('Municipal parking')).toBeInTheDocument();
    expect(screen.getByText('Off-street')).toBeInTheDocument();
    expect(screen.getByText('İBB')).toBeInTheDocument();
    expect(screen.getByText('200')).toBeInTheDocument();
    expect(screen.getByText(/OSM/)).toBeInTheDocument();
    expect(screen.getByText(/38\.423700/)).toBeInTheDocument();
    expect(screen.getByText('Field provenance')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Back to map/i })).toHaveAttribute('href', '/map');

    await waitFor(() => expect(getDetailHits()).toBe(1));
  });

  it('renders location map and marker for valid coordinates', async () => {
    const mapsSpy = vi.spyOn(openMaps, 'openParkingLocationInMaps');
    const { facility, getDetailHits, getNearbyHits } = stubFacility();

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByTestId('municipal-facility-location')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Location' })).toBeInTheDocument();
    const map = screen.getByTestId('spot-map');
    expect(map).toHaveAttribute('data-latitude', String(facility.latitude));
    expect(map).toHaveAttribute('data-longitude', String(facility.longitude));
    expect(map).toHaveAttribute(
      'aria-label',
      'Map of municipal parking facility: Konak Otopark',
    );
    expect(spotMapBehavior.lastProps?.markerPresentation).toBe('municipal');
    expect(spotMapBehavior.mountCount).toBe(1);
    expect(mapsSpy).not.toHaveBeenCalled();

    await waitFor(() => expect(getDetailHits()).toBe(1));
    expect(getNearbyHits()).toBe(0);
  });

  it('calls openParkingLocationInMaps with facility coordinates', async () => {
    const mapsSpy = vi.spyOn(openMaps, 'openParkingLocationInMaps').mockReturnValue(true);
    const { facility } = stubFacility();
    const user = userEvent.setup();

    renderDetail(`/facilities/${FACILITY_ID}`);
    const openBtn = await screen.findByTestId('municipal-facility-open-in-maps');
    expect(openBtn).toHaveAccessibleName(/Open municipal facility location/i);

    await user.click(openBtn);
    expect(mapsSpy).toHaveBeenCalledExactlyOnceWith(facility.latitude, facility.longitude);
  });

  it('supports keyboard activation of open-in-maps', async () => {
    const mapsSpy = vi.spyOn(openMaps, 'openParkingLocationInMaps').mockReturnValue(true);
    const user = userEvent.setup();
    stubFacility();

    renderDetail(`/facilities/${FACILITY_ID}`);
    const openBtn = await screen.findByTestId('municipal-facility-open-in-maps');
    openBtn.focus();
    expect(openBtn).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(mapsSpy).toHaveBeenCalledOnce();
  });

  it('hides map and open-in-maps for invalid latitude', async () => {
    stubFacility({ latitude: 95, longitude: 27.1428 });

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByTestId('municipal-facility-detail')).toBeInTheDocument();
    expect(screen.getByTestId('municipal-facility-location-unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('spot-map')).not.toBeInTheDocument();
    expect(screen.queryByTestId('municipal-facility-open-in-maps')).not.toBeInTheDocument();
    expect(spotMapBehavior.mountCount).toBe(0);
  });

  it('hides map and open-in-maps for invalid longitude', async () => {
    stubFacility({ latitude: 38.4237, longitude: 200 });

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByTestId('municipal-facility-location-unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('spot-map')).not.toBeInTheDocument();
    expect(screen.queryByTestId('municipal-facility-open-in-maps')).not.toBeInTheDocument();
  });

  it('shows location unavailable for non-finite coordinates', async () => {
    stubFacility({ latitude: Number.NaN, longitude: 27.1428 });

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByTestId('municipal-facility-location-unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('spot-map')).not.toBeInTheDocument();
    expect(screen.getByTestId('municipal-facility-location-unavailable')).toHaveTextContent(
      /Location isn't available/i,
    );
  });

  it('keeps textual detail and open-in-maps when map onError fires', async () => {
    spotMapBehavior.failViaOnError = true;
    const mapsSpy = vi.spyOn(openMaps, 'openParkingLocationInMaps').mockReturnValue(true);
    const user = userEvent.setup();
    stubFacility();

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByTestId('municipal-facility-detail')).toBeInTheDocument();
    expect(await screen.findByTestId('municipal-facility-map-unavailable')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Konak Otopark' })).toBeInTheDocument();
    expect(screen.queryByTestId('spot-map')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('municipal-facility-open-in-maps'));
    expect(mapsSpy).toHaveBeenCalledOnce();
  });

  it('keeps textual detail when map render throws', async () => {
    spotMapBehavior.throwOnRender = true;
    stubFacility();

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByTestId('municipal-facility-detail')).toBeInTheDocument();
    expect(await screen.findByTestId('municipal-facility-map-unavailable')).toBeInTheDocument();
    expect(screen.getByTestId('municipal-facility-open-in-maps')).toBeInTheDocument();
  });

  it('does not render location map UI when discovery flag is off', async () => {
    let detailHits = 0;
    server.use(
      http.get(`${API_BASE}/parking/facilities/${FACILITY_ID}`, () => {
        detailHits += 1;
        return HttpResponse.json(makeMunicipalFacility({ id: FACILITY_ID }));
      }),
    );

    renderDetail(`/facilities/${FACILITY_ID}`, { municipalDiscoveryEnabled: false });

    expect(await screen.findByTestId('municipal-facility-detail-disabled')).toBeInTheDocument();
    expect(screen.queryByTestId('municipal-facility-location')).not.toBeInTheDocument();
    expect(screen.queryByTestId('spot-map')).not.toBeInTheDocument();
    expect(detailHits).toBe(0);
    expect(spotMapBehavior.mountCount).toBe(0);
  });

  it('does not mount map while loading', async () => {
    server.use(
      http.get(`${API_BASE}/parking/facilities/${FACILITY_ID}`, async () => {
        await new Promise((resolve) => setTimeout(resolve, 80));
        return HttpResponse.json(makeMunicipalFacility({ id: FACILITY_ID }));
      }),
    );

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(screen.getByTestId('municipal-facility-detail-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('spot-map')).not.toBeInTheDocument();
    expect(await screen.findByTestId('municipal-facility-detail')).toBeInTheDocument();
    expect(screen.getByTestId('spot-map')).toBeInTheDocument();
  });

  it('does not mount map on 404', async () => {
    server.use(
      http.get(`${API_BASE}/parking/facilities/${FACILITY_ID}`, () =>
        HttpResponse.json({ code: 'NOT_FOUND' }, { status: 404 }),
      ),
    );

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByTestId('municipal-facility-detail-not-found')).toBeInTheDocument();
    expect(screen.queryByTestId('spot-map')).not.toBeInTheDocument();
    expect(spotMapBehavior.mountCount).toBe(0);
  });

  it('does not mount map on network error', async () => {
    server.use(
      http.get(`${API_BASE}/parking/facilities/${FACILITY_ID}`, () =>
        HttpResponse.json({ code: 'INTERNAL' }, { status: 500 }),
      ),
    );

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByTestId('municipal-facility-detail-error')).toBeInTheDocument();
    expect(screen.queryByTestId('spot-map')).not.toBeInTheDocument();
    expect(spotMapBehavior.mountCount).toBe(0);
  });

  it('rejects invalid facility ids without calling the API', async () => {
    let detailHits = 0;
    server.use(
      http.get(`${API_BASE}/parking/facilities/:id`, () => {
        detailHits += 1;
        return HttpResponse.json(makeMunicipalFacility());
      }),
    );

    renderDetail('/facilities/not-a-uuid');

    expect(await screen.findByTestId('municipal-facility-detail-invalid')).toBeInTheDocument();
    expect(detailHits).toBe(0);
    expect(spotMapBehavior.mountCount).toBe(0);
  });

  it('renders Turkish location strings', async () => {
    await withLocale('tr');
    stubFacility();

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByRole('heading', { name: 'Konum' })).toBeInTheDocument();
    expect(screen.getByTestId('municipal-facility-open-in-maps')).toHaveTextContent('Haritalarda aç');
  });

  it('renders English location strings', async () => {
    await withLocale('en');
    stubFacility();

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByRole('heading', { name: 'Location' })).toBeInTheDocument();
    expect(screen.getByTestId('municipal-facility-open-in-maps')).toHaveTextContent('Open in maps');
  });

  it('does not invent availability fields on the detail page', async () => {
    stubFacility({ availableSpaces: null, capacityTotal: null });

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByTestId('municipal-facility-detail')).toBeInTheDocument();
    expect(screen.getByText('Live availability not published')).toBeInTheDocument();
    expect(screen.queryByText(/ETA|traffic|directions/i)).not.toBeInTheDocument();
  });
});
