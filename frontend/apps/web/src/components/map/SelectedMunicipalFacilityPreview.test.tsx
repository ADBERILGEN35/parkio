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
    expect(screen.getByText('Availability unavailable')).toBeInTheDocument();
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
});
