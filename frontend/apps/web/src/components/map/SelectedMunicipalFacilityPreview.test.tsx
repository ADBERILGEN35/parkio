import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { SelectedMunicipalFacilityPreview } from './SelectedMunicipalFacilityPreview';
import { makeMunicipalFacility } from '@/test/municipalFixtures';
import { renderWithProviders } from '@/test/utils';

describe('SelectedMunicipalFacilityPreview', () => {
  it('shows normalized data source without field provenance', () => {
    const onClose = vi.fn();
    renderWithProviders(
      <SelectedMunicipalFacilityPreview
        facility={makeMunicipalFacility()}
        distanceMeters={420}
        onClose={onClose}
      />,
    );

    expect(screen.getByTestId('selected-municipal-facility-preview')).toBeInTheDocument();
    expect(screen.getByText('Municipal parking')).toBeInTheDocument();
    expect(screen.getByText('Konak Otopark')).toBeInTheDocument();
    expect(screen.getByText('OpenStreetMap')).toBeInTheDocument();
    expect(screen.getByText('Static facility information')).toBeInTheDocument();
    expect(screen.getByTestId('municipal-availability-copy')).toHaveTextContent(
      'Live occupancy is not shared',
    );
    expect(screen.queryByText('Field provenance')).not.toBeInTheDocument();
    expect(screen.queryByText('Alan kaynağı')).not.toBeInTheDocument();
    expect(screen.queryByText('ATTRIBUTION')).not.toBeInTheDocument();
    expect(screen.queryByText('COORDINATES')).not.toBeInTheDocument();
    expect(screen.queryByText(/Geofabrik/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('Close preview'));
    expect(onClose).toHaveBeenCalled();
  });

  it('does not offer claim or community spot actions', () => {
    renderWithProviders(
      <SelectedMunicipalFacilityPreview
        facility={makeMunicipalFacility()}
        onClose={() => undefined}
      />,
    );
    expect(screen.queryByText(/claim/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/View spot details/i)).not.toBeInTheDocument();
  });

  it('shows multi-source labels in deterministic order', () => {
    renderWithProviders(
      <SelectedMunicipalFacilityPreview
        facility={makeMunicipalFacility({
          contributingSourceKeys: ['osm-geofabrik-turkey', 'izmir-izum-otoparklar'],
          sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
        })}
        onClose={() => undefined}
      />,
    );
    expect(screen.getByText('Data sources')).toBeInTheDocument();
    expect(
      screen.getByText('İzmir Büyükşehir Belediyesi / İZUM · OpenStreetMap'),
    ).toBeInTheDocument();
  });

  it('links to the dedicated municipal facility detail route', () => {
    const facility = makeMunicipalFacility();
    renderWithProviders(
      <SelectedMunicipalFacilityPreview facility={facility} onClose={() => undefined} />,
    );
    expect(screen.getByTestId('municipal-facility-view-details')).toHaveAttribute(
      'href',
      `/facilities/${facility.id}`,
    );
  });

  it('passes discovery distance on the facility detail deep-link', () => {
    const facility = makeMunicipalFacility();
    renderWithProviders(
      <SelectedMunicipalFacilityPreview
        facility={facility}
        distanceMeters={650.4}
        onClose={() => undefined}
      />,
    );
    expect(screen.getByTestId('municipal-facility-view-details')).toHaveAttribute(
      'href',
      `/facilities/${facility.id}?d=650`,
    );
  });

  it('shows live İZUM availability distinctly from static OSM', () => {
    renderWithProviders(
      <SelectedMunicipalFacilityPreview
        facility={makeMunicipalFacility({
          contributingSourceKeys: ['izmir-izum-otoparklar'],
          sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
          availableSpaces: 42,
          capacityTotal: 100,
          freshness: 'LIVE',
          availabilityFreshness: 'LIVE',
          availabilitySource: 'izmir-izum-otoparklar',
        })}
        onClose={() => undefined}
      />,
    );
    expect(screen.getByTestId('municipal-occupancy-status')).toHaveTextContent('Live occupancy');
    expect(screen.getByTestId('municipal-availability-copy')).toHaveTextContent('42 open / 100 spaces');
  });

  it('shows stale-live copy for İZUM STALE, not static-source copy', () => {
    renderWithProviders(
      <SelectedMunicipalFacilityPreview
        facility={makeMunicipalFacility({
          contributingSourceKeys: ['izmir-izum-otoparklar'],
          sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
          availableSpaces: null,
          capacityTotal: 133,
          freshness: 'STALE',
          availabilityFreshness: null,
        })}
        onClose={() => undefined}
      />,
    );
    expect(screen.getByTestId('municipal-occupancy-status')).toHaveTextContent(
      'Live data temporarily out of date',
    );
    expect(screen.getByTestId('municipal-availability-copy')).toHaveTextContent(
      'Live data is temporarily out of date',
    );
    expect(screen.queryByText('Static facility information')).not.toBeInTheDocument();
  });
});
