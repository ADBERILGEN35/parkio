import type { PublicSpot } from '@parkio/types';
import { fireEvent, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';
import { makeMunicipalFacility } from '@/test/municipalFixtures';
import { renderWithProviders } from '@/test/utils';
import { NearbySpotsMap } from './NearbySpotsMap';

// MapLibre/WebGL cannot run in jsdom. Stub react-map-gl with lightweight DOM so
// the React-driven markers can be asserted without a real GL canvas.
vi.mock('react-map-gl/maplibre', () => ({
  __esModule: true,
  default: ({ children }: { children?: React.ReactNode }) => <div data-testid="map">{children}</div>,
  Marker: ({
    children,
    longitude,
    latitude,
  }: {
    children?: React.ReactNode;
    longitude: number;
    latitude: number;
  }) => (
    <div data-testid="marker" data-lng={longitude} data-lat={latitude}>
      {children}
    </div>
  ),
  useMap: () => ({ current: null }),
}));

function makeSpot(
  overrides: Partial<PublicSpot> & Pick<PublicSpot, 'id' | 'latitude' | 'longitude'>,
): PublicSpot {
  return {
    mediaId: '0b8f6c3a-0000-0000-0000-000000000099',
    addressText: 'A Spot',
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
    ...overrides,
  };
}

const spots: PublicSpot[] = [
  makeSpot({ id: 'spot-1', latitude: 41.11, longitude: 29.11, addressText: 'First Spot' }),
  makeSpot({ id: 'spot-2', latitude: 41.22, longitude: 29.22, addressText: 'Second Spot' }),
];

describe('NearbySpotsMap', () => {
  it('exposes the map as an accessible region with instructions and a live selection summary', async () => {
    const { container } = renderWithProviders(
      <NearbySpotsMap
        center={{ lat: 41, lng: 29 }}
        spots={spots}
        onPickCenter={() => undefined}
        ariaLabel="Interactive parking discovery map"
        ariaDescription="Use Tab to move to map markers."
        selectionSummary="Selected community spot: First Spot"
      />,
    );

    expect(
      screen.getByRole('region', { name: 'Interactive parking discovery map' }),
    ).toHaveAccessibleDescription('Use Tab to move to map markers. Selected community spot: First Spot');
    expect(screen.getByRole('status')).toHaveTextContent('Selected community spot: First Spot');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('renders a marker for each spot plus the search-center indicator', () => {
    renderWithProviders(
      <NearbySpotsMap center={{ lat: 41, lng: 29 }} spots={spots} onPickCenter={() => undefined} />,
    );

    // 2 spots + 1 center indicator.
    expect(screen.getAllByTestId('marker')).toHaveLength(3);
  });

  it('reports the chosen spot id when a marker is clicked (controlled selection)', () => {
    const onSelectSpot = vi.fn();
    renderWithProviders(
      <NearbySpotsMap
        center={{ lat: 41, lng: 29 }}
        spots={spots}
        onPickCenter={() => undefined}
        onSelectSpot={onSelectSpot}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /first spot/i }));

    expect(onSelectSpot).toHaveBeenCalledWith('spot-1');
  });

  it('marks the controlled selected marker as active', () => {
    renderWithProviders(
      <NearbySpotsMap
        center={{ lat: 41, lng: 29 }}
        spots={spots}
        onPickCenter={() => undefined}
        selectedId="spot-1"
      />,
    );

    expect(screen.getByRole('button', { name: /first spot/i })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: /second spot/i })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('does not set the search center when a spot marker is clicked', () => {
    const onPickCenter = vi.fn();
    const onSelectSpot = vi.fn();
    renderWithProviders(
      <NearbySpotsMap
        center={{ lat: 41, lng: 29 }}
        spots={spots}
        onPickCenter={onPickCenter}
        onSelectSpot={onSelectSpot}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /second spot/i }));

    // Marker click selects the spot; it must not trigger the map's pick-center.
    expect(onPickCenter).not.toHaveBeenCalled();
    expect(onSelectSpot).toHaveBeenCalledWith('spot-2');
  });

  it('renders a dedicated parked-car marker distinct from spot markers', () => {
    const onSelectParkedCar = vi.fn();
    const onSelectSpot = vi.fn();
    renderWithProviders(
      <NearbySpotsMap
        center={{ lat: 41, lng: 29 }}
        spots={spots}
        onPickCenter={() => undefined}
        onSelectSpot={onSelectSpot}
        parkedCar={{ latitude: 38.42, longitude: 27.14 }}
        parkedCarSelected
        onSelectParkedCar={onSelectParkedCar}
      />,
    );

    // 2 spots + 1 center + 1 parked car
    expect(screen.getAllByTestId('marker')).toHaveLength(4);
    const parked = screen.getByTestId('parked-car-marker');
    expect(parked).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(parked);
    expect(onSelectParkedCar).toHaveBeenCalledOnce();
    expect(onSelectSpot).not.toHaveBeenCalled();
  });

  it('renders municipal facility markers distinctly from community spots', () => {
    const onSelectMunicipal = vi.fn();
    const facilities = [
      makeMunicipalFacility({ id: 'fac-a', latitude: 38.4, longitude: 27.1, displayName: 'Lot A' }),
    ];

    renderWithProviders(
      <NearbySpotsMap
        center={{ lat: 41, lng: 29 }}
        spots={spots}
        municipalFacilities={facilities}
        onPickCenter={() => undefined}
        onSelectMunicipalFacility={onSelectMunicipal}
      />,
    );

    // 2 spots + 1 center + 1 municipal
    expect(screen.getAllByTestId('marker')).toHaveLength(4);
    expect(screen.getByTestId('municipal-facility-marker')).toBeInTheDocument();
    expect(screen.getAllByTestId('community-spot-marker')).toHaveLength(2);

    fireEvent.click(screen.getByTestId('municipal-facility-marker'));
    expect(onSelectMunicipal).toHaveBeenCalledWith('fac-a');
  });
});
