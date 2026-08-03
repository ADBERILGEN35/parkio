import type { MunicipalFacility, NearbySearchParams } from '@parkio/types';
import { fireEvent, screen } from '@testing-library/react';
import type { UseQueryResult } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { MunicipalFacilityResults } from './MunicipalFacilityResults';
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

describe('MunicipalFacilityResults', () => {
  it('shows loading state', () => {
    renderWithProviders(
      <MunicipalFacilityResults
        search={queryResult({ isPending: true, status: 'pending' })}
        params={params}
        facilities={[]}
        selectedId={null}
        onSelect={() => undefined}
      />,
    );
    expect(screen.getByTestId('municipal-facility-loading')).toBeInTheDocument();
  });

  it('shows empty state', () => {
    renderWithProviders(
      <MunicipalFacilityResults
        search={queryResult({ isSuccess: true, status: 'success', data: [] })}
        params={params}
        facilities={[]}
        selectedId={null}
        onSelect={() => undefined}
      />,
    );
    expect(screen.getByTestId('municipal-facility-empty')).toBeInTheDocument();
    expect(screen.getByText('No municipal facilities nearby')).toBeInTheDocument();
  });

  it('shows error state', () => {
    renderWithProviders(
      <MunicipalFacilityResults
        search={queryResult({
          isError: true,
          status: 'error',
          error: new Error('boom'),
        })}
        params={params}
        facilities={[]}
        selectedId={null}
        onSelect={() => undefined}
      />,
    );
    expect(screen.getByTestId('municipal-facility-error')).toBeInTheDocument();
  });

  it('lists facilities and selects on click', () => {
    const onSelect = vi.fn();
    const facility = makeMunicipalFacility({ id: 'fac-9', latitude: 38.42, longitude: 27.14 });
    renderWithProviders(
      <MunicipalFacilityResults
        search={queryResult({ isSuccess: true, status: 'success', data: [facility] })}
        params={params}
        facilities={[facility]}
        selectedId={null}
        onSelect={onSelect}
      />,
    );
    expect(screen.getByTestId('municipal-facility-list')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('municipal-facility-result'));
    expect(onSelect).toHaveBeenCalledWith('fac-9');
  });

  it('renders nothing before a search center exists', () => {
    const { container } = renderWithProviders(
      <MunicipalFacilityResults
        search={queryResult({ isPending: true })}
        params={null}
        facilities={[]}
        selectedId={null}
        onSelect={() => undefined}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
