import type { PublicSpot } from '@parkio/types';
import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/utils';
import { NearbySpotsMap } from './NearbySpotsMap';

// MapLibre/WebGL cannot run in jsdom. Stub react-map-gl with lightweight DOM so
// the React-driven markers and popups can be asserted without a real GL canvas.
vi.mock('react-map-gl/maplibre', () => ({
  __esModule: true,
  default: ({ children }: { children?: React.ReactNode }) => <div data-testid="map">{children}</div>,
  Marker: ({
    children,
    longitude,
    latitude,
    onClick,
  }: {
    children?: React.ReactNode;
    longitude: number;
    latitude: number;
    onClick?: (e: { originalEvent: { stopPropagation: () => void } }) => void;
  }) => (
    <button
      type="button"
      data-testid="marker"
      data-lng={longitude}
      data-lat={latitude}
      onClick={() => onClick?.({ originalEvent: { stopPropagation: () => undefined } })}
    >
      {children}
    </button>
  ),
  Popup: ({ children }: { children?: React.ReactNode }) => <div data-testid="popup">{children}</div>,
  useMap: () => ({ current: null }),
}));

function makeSpot(overrides: Partial<PublicSpot> & Pick<PublicSpot, 'id' | 'latitude' | 'longitude'>): PublicSpot {
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

  it('opens a popup with the spot details and a "View spot" link on marker click', () => {
    renderWithProviders(
      <NearbySpotsMap center={{ lat: 41, lng: 29 }} spots={spots} onPickCenter={() => undefined} />,
    );

    expect(screen.queryByTestId('popup')).not.toBeInTheDocument();

    const firstMarker = screen
      .getAllByTestId('marker')
      .find((el) => el.getAttribute('data-lat') === '41.11');
    expect(firstMarker).toBeDefined();
    fireEvent.click(firstMarker as HTMLElement);

    const popup = screen.getByTestId('popup');
    expect(popup).toHaveTextContent('First Spot');
    const link = screen.getByRole('link', { name: 'View spot' });
    expect(link).toHaveAttribute('href', '/spots/spot-1');
  });

  it('does not set the search center when a spot marker is clicked', () => {
    const onPickCenter = vi.fn();
    renderWithProviders(
      <NearbySpotsMap center={{ lat: 41, lng: 29 }} spots={spots} onPickCenter={onPickCenter} />,
    );

    const marker = screen
      .getAllByTestId('marker')
      .find((el) => el.getAttribute('data-lat') === '41.22');
    fireEvent.click(marker as HTMLElement);

    // Marker click opens the popup; it must not trigger the map's pick-center.
    expect(onPickCenter).not.toHaveBeenCalled();
    expect(screen.getByTestId('popup')).toHaveTextContent('Second Spot');
  });
});
