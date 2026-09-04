import type { ComponentProps } from 'react';
import type { MunicipalFacility, NearbySearchParams } from '@parkio/types';
import { fireEvent, screen } from '@testing-library/react';
import type { UseQueryResult } from '@tanstack/react-query';
import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';
import { MunicipalFacilityResults } from './MunicipalFacilityResults';
import { EMPTY_MUNICIPAL_FILTERS } from '@/lib/spotDiscovery';
import { makeMunicipalFacility } from '@/test/municipalFixtures';
import { renderWithProviders } from '@/test/utils';

function queryResult(
  partial: Partial<UseQueryResult<MunicipalFacility[], Error>>,
): UseQueryResult<MunicipalFacility[], Error> {
  return {
    data: undefined,
    error: null,
    isError: false,
    isPending: false,
    isSuccess: false,
    status: 'pending',
    fetchStatus: 'idle',
    refetch: vi.fn(),
    ...partial,
  } as UseQueryResult<MunicipalFacility[], Error>;
}

const params: NearbySearchParams = { lat: 38.4237, lng: 27.1428, radius: 1000 };

const RAW_OSM_SOURCE_LABEL = 'OpenStreetMap contributors / Geofabrik GmbH';

function renderResults(
  overrides: Partial<ComponentProps<typeof MunicipalFacilityResults>> = {},
) {
  const facility = makeMunicipalFacility({ id: 'fac-9', latitude: 38.42, longitude: 27.14 });
  return renderWithProviders(
    <MunicipalFacilityResults
      search={queryResult({ isSuccess: true, status: 'success', data: [facility] })}
      params={params}
      facilities={[facility]}
      totalCount={1}
      filters={EMPTY_MUNICIPAL_FILTERS}
      onFiltersChange={() => undefined}
      availableSourceLabels={[RAW_OSM_SOURCE_LABEL]}
      availableFacilityTypes={['OFF_STREET']}
      selectedId={null}
      onSelect={() => undefined}
      {...overrides}
    />,
  );
}

describe('MunicipalFacilityResults', () => {
  it('shows loading state', () => {
    renderResults({
      search: queryResult({ isPending: true, status: 'pending' }),
      facilities: [],
      totalCount: 0,
    });
    expect(screen.getByTestId('municipal-facility-loading')).toBeInTheDocument();
  });

  it('shows empty state', () => {
    renderResults({
      search: queryResult({ isSuccess: true, status: 'success', data: [] }),
      facilities: [],
      totalCount: 0,
    });
    expect(screen.getByTestId('municipal-facility-empty')).toBeInTheDocument();
    expect(screen.getByText('No municipal facilities nearby')).toBeInTheDocument();
  });

  it('shows error state', () => {
    renderResults({
      search: queryResult({
        isError: true,
        status: 'error',
        error: new Error('boom'),
      }),
      facilities: [],
      totalCount: 0,
    });
    expect(screen.getByTestId('municipal-facility-error')).toBeInTheDocument();
  });

  it('lists facilities and selects on click', () => {
    const onSelect = vi.fn();
    const facility = makeMunicipalFacility({ id: 'fac-9', latitude: 38.42, longitude: 27.14 });
    renderResults({
      search: queryResult({ isSuccess: true, status: 'success', data: [facility] }),
      facilities: [facility],
      totalCount: 1,
      selectedId: null,
      onSelect,
    });
    fireEvent.click(screen.getByTestId('municipal-facility-result'));
    expect(onSelect).toHaveBeenCalledWith('fac-9');
  });

  it('exposes bounded accessible names for municipal result buttons', () => {
    renderResults();
    expect(screen.getByRole('button', { name: /municipal parking/i })).toBeInTheDocument();
  });

  it('renders filter chips and announces filter state', () => {
    renderResults();
    expect(screen.getByTestId('municipal-facility-filters')).toBeInTheDocument();
    expect(screen.getByTestId('municipal-filter-availability-available')).toBeInTheDocument();
    expect(screen.getByTestId('municipal-filter-source')).toHaveTextContent('OpenStreetMap');
    expect(screen.getByTestId('municipal-filter-type-OFF_STREET')).toBeInTheDocument();
    expect(screen.getByTestId('municipal-filter-provenance')).toBeInTheDocument();
    expect(screen.getByText(/1 municipal facilities shown after filters/i)).toBeInTheDocument();
  });

  it('shows canonical source on list cards without raw backend strings', () => {
    renderResults();
    expect(screen.getByTestId('municipal-facility-result')).toHaveTextContent('OpenStreetMap');
    expect(screen.queryByText(/Geofabrik/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/osm-geofabrik-turkey/i)).not.toBeInTheDocument();
  });

  it('toggles availability and source filters', () => {
    const onFiltersChange = vi.fn();
    renderResults({ onFiltersChange });

    fireEvent.click(screen.getByTestId('municipal-filter-availability-unknown'));
    expect(onFiltersChange).toHaveBeenCalledWith(
      expect.objectContaining({ availability: 'unknown' }),
    );

    fireEvent.click(screen.getByTestId('municipal-filter-source'));
    expect(onFiltersChange).toHaveBeenCalledWith(
      expect.objectContaining({ sourceLabels: [RAW_OSM_SOURCE_LABEL] }),
    );

    fireEvent.click(screen.getByTestId('municipal-filter-type-OFF_STREET'));
    expect(onFiltersChange).toHaveBeenCalledWith(
      expect.objectContaining({ facilityTypes: ['OFF_STREET'] }),
    );

    fireEvent.click(screen.getByTestId('municipal-filter-provenance'));
    expect(onFiltersChange).toHaveBeenCalledWith(
      expect.objectContaining({ provenanceOnly: true }),
    );
  });

  it('shows filtered-empty state and clear control when filters hide all', () => {
    const onFiltersChange = vi.fn();
    renderResults({
      facilities: [],
      totalCount: 2,
      filters: { ...EMPTY_MUNICIPAL_FILTERS, availability: 'available' },
      onFiltersChange,
    });
    expect(screen.getByTestId('municipal-facility-filtered-empty')).toBeInTheDocument();
    expect(screen.getByText('0 of 2 facilities')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('municipal-filter-clear'));
    expect(onFiltersChange).toHaveBeenCalledWith(EMPTY_MUNICIPAL_FILTERS);
  });

  it('keeps a marker-driven selected facility in view accessibly', () => {
    const scrollIntoView = vi.fn();
    const previous = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = scrollIntoView;

    try {
      const facility = makeMunicipalFacility({
        id: 'fac-9',
        latitude: 38.42,
        longitude: 27.14,
        displayName: 'Hatay Katli Pazaryeri',
      });
      renderResults({
        search: queryResult({ isSuccess: true, status: 'success', data: [facility] }),
        facilities: [facility],
        totalCount: 1,
        selectedId: 'fac-9',
        selectionFromMap: true,
      });

      expect(screen.getByTestId('municipal-facility-result')).toHaveAttribute('aria-pressed', 'true');
      expect(scrollIntoView).toHaveBeenCalled();
    } finally {
      Element.prototype.scrollIntoView = previous;
    }
  });

  it('shows live vs static occupancy summary without raw source keys', () => {
    const osm = makeMunicipalFacility({ id: 'fac-osm' });
    const izum = makeMunicipalFacility({
      id: 'fac-izum',
      contributingSourceKeys: ['izmir-izum-otoparklar'],
      availableSpaces: 5,
      freshness: 'LIVE',
      availabilityFreshness: 'LIVE',
      sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
    });
    renderResults({
      search: queryResult({ isSuccess: true, status: 'success', data: [osm, izum] }),
      facilities: [osm, izum],
      totalCount: 2,
      availableSourceLabels: [RAW_OSM_SOURCE_LABEL, 'Izmir Buyuksehir Belediyesi / IZUM'],
    });
    expect(screen.getByTestId('municipal-occupancy-summary')).toHaveTextContent(
      /2 facilities · 1 live occupancy · 1 static info only/i,
    );
    expect(screen.queryByText(/osm-geofabrik-turkey/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/izmir-izum-otoparklar/i)).not.toBeInTheDocument();
  });

  it('has no automated accessibility violations in the success state', async () => {
    const { container } = renderResults();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('does not force list scroll for list-origin selection', () => {
    const scrollIntoView = vi.fn();
    const previous = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = scrollIntoView;

    try {
      renderResults({ selectedId: 'fac-9', selectionFromMap: false });
      expect(scrollIntoView).not.toHaveBeenCalled();
    } finally {
      Element.prototype.scrollIntoView = previous;
    }
  });
});
