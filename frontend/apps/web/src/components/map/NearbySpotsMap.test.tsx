import type { PublicSpot } from '@parkio/types';
import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
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
});
