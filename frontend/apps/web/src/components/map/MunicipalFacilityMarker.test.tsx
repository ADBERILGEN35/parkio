import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MunicipalFacilityMarker } from './MunicipalFacilityMarker';
import { makeMunicipalFacility } from '@/test/municipalFixtures';
import { renderWithProviders } from '@/test/utils';

describe('MunicipalFacilityMarker', () => {
  it('renders a distinguishable municipal marker and selects on click', () => {
    const onSelect = vi.fn();
    const facility = makeMunicipalFacility({ id: 'fac-1', latitude: 38.4, longitude: 27.1 });

    renderWithProviders(
      <MunicipalFacilityMarker facility={facility} selected={false} onSelect={onSelect} />,
    );

    const marker = screen.getByTestId('municipal-facility-marker');
    expect(marker).toHaveAttribute('data-facility-id', 'fac-1');
    expect(marker).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(marker);
    expect(onSelect).toHaveBeenCalledWith('fac-1');
  });

  it('marks selection state for accessibility', () => {
    renderWithProviders(
      <MunicipalFacilityMarker
        facility={makeMunicipalFacility()}
        selected
        onSelect={() => undefined}
      />,
    );
    expect(screen.getByTestId('municipal-facility-marker')).toHaveAttribute('aria-pressed', 'true');
  });
});
