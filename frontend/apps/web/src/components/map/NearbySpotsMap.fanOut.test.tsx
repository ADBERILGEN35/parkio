import { act, fireEvent, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeMunicipalFacility } from '@/test/municipalFixtures';
import { renderWithProviders } from '@/test/utils';
import { NearbySpotsMap } from './NearbySpotsMap';

/**
 * Asana 1219147334320125, owner decision 2026-10-05: on Public Explore, municipal markers that would cover
 * one another fan out. Only the drawn marker moves, by a pixel offset; coordinates, names, selection and
 * the Tab order do not change. The map is a stand-in whose projection scale plays the zoom.
 */
const fakeMap = vi.hoisted(() => {
  const handlers: Record<string, Set<() => void>> = {};
  const view = { pxPerDegree: 1000 };
  const container = document.createElement('div');
  container.getBoundingClientRect = () =>
    ({ top: 0, left: 0, right: 1000, bottom: 600, width: 1000, height: 600, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
  return {
    view,
    project: ([lng, lat]: [number, number]) => ({
      x: 500 + (lng - 27.14) * view.pxPerDegree,
      y: 300 - (lat - 38.43) * view.pxPerDegree,
    }),
    getContainer: () => container,
    on: (event: string, handler: () => void) => {
      (handlers[event] ??= new Set()).add(handler);
    },
    off: (event: string, handler: () => void) => {
      handlers[event]?.delete(handler);
    },
    fire: (event: string) => handlers[event]?.forEach((handler) => handler()),
    easeTo: () => undefined,
    getZoom: () => 13,
  };
});

vi.mock('react-map-gl/maplibre', () => ({
  __esModule: true,
  default: ({ children }: { children?: React.ReactNode }) => <div data-testid="map">{children}</div>,
  Marker: ({
    children,
    longitude,
    latitude,
    offset,
    className,
  }: {
    children?: React.ReactNode;
    longitude: number;
    latitude: number;
    offset?: [number, number];
    className?: string;
  }) => (
    <div
      data-testid="marker"
      data-lng={longitude}
      data-lat={latitude}
      data-offset={offset ? offset.join(',') : 'none'}
      data-class={className ?? ''}
    >
      {children}
    </div>
  ),
  useMap: () => ({ current: fakeMap }),
}));

// Two car parks at one point, one 30 m away, and one far away.
const twinA = makeMunicipalFacility({ id: 'twin-a', displayName: 'Twin A', latitude: 38.43, longitude: 27.14 });
const twinB = makeMunicipalFacility({ id: 'twin-b', displayName: 'Twin B', latitude: 38.43, longitude: 27.14 });
const near = makeMunicipalFacility({ id: 'near', displayName: 'Near', latitude: 38.43, longitude: 27.14035 });
const far = makeMunicipalFacility({ id: 'far', displayName: 'Far', latitude: 38.5, longitude: 27.3 });
const facilities = [twinA, twinB, near, far];

function facilityMarker(id: string) {
  return document.querySelector<HTMLElement>(`[data-facility-id="${id}"]`)!;
}

/** The stubbed Marker around a facility marker: its coordinates, offset and classes. */
function markerShell(id: string) {
  return facilityMarker(id).closest<HTMLElement>('[data-testid="marker"]')!;
}

function renderMap(props: Partial<React.ComponentProps<typeof NearbySpotsMap>> = {}) {
  return renderWithProviders(
    <NearbySpotsMap
      center={{ lat: 38.43, lng: 27.14 }}
      spots={[]}
      municipalFacilities={facilities}
      onPickCenter={() => undefined}
      fanOutMunicipalMarkers
      {...props}
    />,
  );
}

describe('NearbySpotsMap fan-out (Public Explore)', () => {
  beforeEach(() => {
    fakeMap.view.pxPerDegree = 1000;
  });

  it('fans out car parks that would cover one another, and ties each to its true location', () => {
    renderMap();

    // The twins share a pixel and "near" is 0.35 px away at this scale: one group of three, on a ring.
    const offsets = ['twin-a', 'twin-b', 'near'].map((id) => markerShell(id).dataset.offset);
    expect(new Set(offsets).size).toBe(3);
    expect(offsets).not.toContain('0,0');
    expect(markerShell('far').dataset.offset).toBe('0,0');

    const connectors = screen.getAllByTestId('municipal-facility-fan-connector');
    expect(connectors.map((connector) => connector.getAttribute('data-fan-for'))).toEqual(['twin-a', 'twin-b', 'near']);
    for (const connector of connectors) {
      expect(connector).toHaveAttribute('aria-hidden', 'true');
      expect(connector).toHaveAttribute('focusable', 'false');
      expect(connector.closest('[data-testid="marker"]')).toHaveAttribute('data-class', 'pointer-events-none');
    }
    // Each connector sits at its car park's own coordinates and reaches the drawn marker.
    const twinAConnector = connectors[0];
    const shell = twinAConnector.closest<HTMLElement>('[data-testid="marker"]')!;
    expect([shell.dataset.lat, shell.dataset.lng]).toEqual(['38.43', '27.14']);
    const line = twinAConnector.querySelector('line')!;
    expect(`${line.getAttribute('x2')},${line.getAttribute('y2')}`).toBe(markerShell('twin-a').dataset.offset);
  });

  it('keeps every coordinate, accessible name and the Tab order of the car parks', () => {
    renderMap();

    for (const facility of facilities) {
      const shell = markerShell(facility.id);
      expect([Number(shell.dataset.lat), Number(shell.dataset.lng)]).toEqual([facility.latitude, facility.longitude]);
    }
    const map = screen.getByTestId('map');
    const buttons = within(map).getAllByRole('button');
    expect(buttons.map((button) => button.getAttribute('data-facility-id'))).toEqual(['twin-a', 'twin-b', 'near', 'far']);
    expect(buttons.map((button) => button.getAttribute('aria-label'))).toEqual(
      facilities.map((facility) => `Municipal parking facility: ${facility.displayName}`),
    );
  });

  it('selects the fanned car park that was activated', () => {
    const onSelect = vi.fn();
    renderMap({ onSelectMunicipalFacility: onSelect });

    fireEvent.click(facilityMarker('twin-b'));
    expect(onSelect).toHaveBeenCalledWith('twin-b');
  });

  it('draws the car parks above their connectors, and the focused or selected one above the others', () => {
    renderMap({ selectedMunicipalId: 'twin-b' });

    // The selected marker's pulsing halo would reach its fanned neighbours; it lets the pointer through.
    expect(within(facilityMarker('twin-b')).getByTestId('municipal-facility-marker-halo')).toHaveClass('pointer-events-none');

    expect(markerShell('twin-b').dataset.class).toBe('z-[2]');
    expect(markerShell('twin-a').dataset.class).toBe('z-[1] focus-within:z-[2]');
    expect(markerShell('far').dataset.class).toBe('z-[1] focus-within:z-[2]');
    for (const connector of screen.getAllByTestId('municipal-facility-fan-connector')) {
      expect(connector.closest('[data-testid="marker"]')).toHaveAttribute('data-class', 'pointer-events-none');
    }
  });

  it('follows the zoom: markers that separate go back to their places, and fan out again when they meet', () => {
    renderMap();
    expect(markerShell('near').dataset.offset).not.toBe('0,0');

    // Zoomed in far enough that "near" is 350 px from the twins; the twins still share a point.
    act(() => {
      fakeMap.view.pxPerDegree = 1_000_000;
      fakeMap.fire('moveend');
    });
    expect(markerShell('near').dataset.offset).toBe('0,0');
    expect(markerShell('twin-a').dataset.offset).not.toBe('0,0');
    expect(screen.getAllByTestId('municipal-facility-fan-connector').map((c) => c.getAttribute('data-fan-for'))).toEqual([
      'twin-a',
      'twin-b',
    ]);

    act(() => {
      fakeMap.view.pxPerDegree = 1000;
      fakeMap.fire('moveend');
    });
    expect(markerShell('near').dataset.offset).not.toBe('0,0');
    expect(screen.getAllByTestId('municipal-facility-fan-connector')).toHaveLength(3);
  });

  it('lays the markers out again when the map loads or resizes', () => {
    renderMap();
    act(() => {
      fakeMap.view.pxPerDegree = 1_000_000;
      fakeMap.fire('resize');
    });
    expect(markerShell('near').dataset.offset).toBe('0,0');
    act(() => {
      fakeMap.view.pxPerDegree = 1000;
      fakeMap.fire('load');
    });
    expect(markerShell('near').dataset.offset).not.toBe('0,0');
  });

  it('moves nothing without the prop, as on /map', () => {
    renderMap({ fanOutMunicipalMarkers: false });

    for (const facility of facilities) {
      expect(markerShell(facility.id).dataset.offset).toBe('none');
      expect(markerShell(facility.id).dataset.class).toBe('');
    }
    expect(screen.queryByTestId('municipal-facility-fan-connector')).not.toBeInTheDocument();
  });
});
