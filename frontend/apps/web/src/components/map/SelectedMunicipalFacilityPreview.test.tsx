import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { SelectedMunicipalFacilityPreview } from './SelectedMunicipalFacilityPreview';
import { makeMunicipalFacility } from '@/test/municipalFixtures';
import { renderWithProviders } from '@/test/utils';

describe('SelectedMunicipalFacilityPreview', () => {
  it('shows municipal inventory label, source, freshness, and provenance', () => {
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
    expect(screen.getByText(/OSM/)).toBeInTheDocument();
    expect(screen.getByText('Availability unavailable')).toBeInTheDocument();
    expect(screen.getByText('Field provenance')).toBeInTheDocument();
    expect(screen.getByText('displayName')).toBeInTheDocument();

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
