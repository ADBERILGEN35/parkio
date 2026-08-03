import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MunicipalFacilityDetailPage } from './MunicipalFacilityDetailPage';
import { makeMunicipalFacility } from '@/test/municipalFixtures';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders } from '@/test/utils';

const FACILITY_ID = '70db58f2-4cca-4010-9315-fa46b30fba1e';

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

describe('MunicipalFacilityDetailPage (WEB-MUNI-02)', () => {
  it('loads facility detail from GET /parking/facilities/{id}', async () => {
    const facility = makeMunicipalFacility({
      id: FACILITY_ID,
      operatorName: 'İBB',
      capacityTotal: 200,
    });
    let detailHits = 0;
    server.use(
      http.get(`${API_BASE}/parking/facilities/${FACILITY_ID}`, () => {
        detailHits += 1;
        return HttpResponse.json(facility);
      }),
    );

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

    await waitFor(() => expect(detailHits).toBe(1));
  });

  it('shows 404 empty state for unknown facility', async () => {
    server.use(
      http.get(`${API_BASE}/parking/facilities/${FACILITY_ID}`, () =>
        HttpResponse.json({ code: 'NOT_FOUND' }, { status: 404 }),
      ),
    );

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByTestId('municipal-facility-detail-not-found')).toBeInTheDocument();
    expect(screen.getByText('Facility not found')).toBeInTheDocument();
  });

  it('shows network failure without inventing facility data', async () => {
    server.use(
      http.get(`${API_BASE}/parking/facilities/${FACILITY_ID}`, () =>
        HttpResponse.json({ code: 'INTERNAL' }, { status: 500 }),
      ),
    );

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(await screen.findByTestId('municipal-facility-detail-error')).toBeInTheDocument();
    expect(screen.queryByTestId('municipal-facility-detail')).not.toBeInTheDocument();
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
  });

  it('hides municipal UI when discovery flag is off', async () => {
    let detailHits = 0;
    server.use(
      http.get(`${API_BASE}/parking/facilities/${FACILITY_ID}`, () => {
        detailHits += 1;
        return HttpResponse.json(makeMunicipalFacility({ id: FACILITY_ID }));
      }),
    );

    renderDetail(`/facilities/${FACILITY_ID}`, { municipalDiscoveryEnabled: false });

    expect(await screen.findByTestId('municipal-facility-detail-disabled')).toBeInTheDocument();
    expect(screen.queryByTestId('municipal-facility-detail')).not.toBeInTheDocument();
    expect(detailHits).toBe(0);
  });

  it('shows loading state while the detail query is pending', async () => {
    server.use(
      http.get(`${API_BASE}/parking/facilities/${FACILITY_ID}`, async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
        return HttpResponse.json(makeMunicipalFacility({ id: FACILITY_ID }));
      }),
    );

    renderDetail(`/facilities/${FACILITY_ID}`);

    expect(screen.getByTestId('municipal-facility-detail-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('municipal-facility-detail')).toBeInTheDocument();
  });
});
