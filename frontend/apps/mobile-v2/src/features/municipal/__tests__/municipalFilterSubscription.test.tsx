import { useEffect, useRef } from 'react';
import { Text } from 'react-native';
import { act, render, renderHook, waitFor } from '@testing-library/react-native';

const mockReadJson = jest.fn();
const mockWriteJson = jest.fn();

jest.mock('@/services/jsonStore', () => ({
  readJson: (...args: unknown[]) => mockReadJson(...args),
  writeJson: (...args: unknown[]) => mockWriteJson(...args),
}));

import { DEFAULT_MUNICIPAL_MAP_FILTERS } from '../municipalFilterModel';
import {
  useMunicipalFilterStore,
  useMunicipalMapFilters,
} from '../municipalFilterStore';

/** Unsafe pattern from 921706f — allocates a new object on every snapshot. */
function unsafeSelectMunicipalMapFilters(state: {
  version: number;
  layerEnabled: boolean;
  source: string;
  occupancy: string;
  radiusMeters: number;
}) {
  return {
    version: state.version,
    layerEnabled: state.layerEnabled,
    source: state.source,
    occupancy: state.occupancy,
    radiusMeters: state.radiusMeters,
  };
}

function resetStore() {
  useMunicipalFilterStore.setState({
    ...DEFAULT_MUNICIPAL_MAP_FILTERS,
    hydrated: false,
  });
}

describe('useMunicipalMapFilters subscription stability', () => {
  let consoleErrorSpy: jest.SpyInstance;

  beforeEach(() => {
    jest.clearAllMocks();
    resetStore();
    consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation((...args: unknown[]) => {
      const message = args.map(String).join(' ');
      if (
        message.includes('getSnapshot should be cached') ||
        message.includes('Maximum update depth exceeded')
      ) {
        throw new Error(message);
      }
    });
  });

  afterEach(() => {
    consoleErrorSpy.mockRestore();
  });

  it('does not continuously rerender when store state is unchanged', async () => {
    const renders = { count: 0 };
    function Probe() {
      const filters = useMunicipalMapFilters();
      renders.count += 1;
      return <Text testID="probe">{`${filters.source}:${filters.radiusMeters}`}</Text>;
    }

    const { getByTestId, rerender } = render(<Probe />);
    const baseline = renders.count;
    expect(baseline).toBeGreaterThan(0);
    expect(getByTestId('probe').props.children).toBe('all:1500');

    // Force several parent rerenders without store changes.
    for (let i = 0; i < 5; i += 1) {
      rerender(<Probe />);
    }

    await waitFor(() => {
      expect(getByTestId('probe').props.children).toBe('all:1500');
    });
    // Parent rerenders remount the probe body, but must stay bounded (no loop).
    expect(renders.count).toBeLessThanOrEqual(baseline + 8);
  });

  it('keeps a stable object identity across idle store notifications', () => {
    const { result } = renderHook(() => useMunicipalMapFilters());
    const first = result.current;

    act(() => {
      // Touch hydrated only — not part of the filter snapshot.
      useMunicipalFilterStore.setState({ hydrated: true });
    });

    expect(result.current).toBe(first);
    expect(result.current.source).toBe('all');
  });

  it('updates once for layer, source, occupancy, and radius changes', () => {
    const { result } = renderHook(() => useMunicipalMapFilters());

    act(() => {
      useMunicipalFilterStore.getState().setLayerEnabled(false);
    });
    expect(result.current.layerEnabled).toBe(false);

    act(() => {
      useMunicipalFilterStore.getState().setSource('izum');
    });
    expect(result.current.source).toBe('izum');

    act(() => {
      useMunicipalFilterStore.getState().setOccupancy('live');
    });
    expect(result.current.occupancy).toBe('live');

    act(() => {
      useMunicipalFilterStore.getState().setRadiusMeters(3000);
    });
    expect(result.current.radiusMeters).toBe(3000);
  });

  it('reset restores defaults while preserving layer visibility', () => {
    const { result } = renderHook(() => useMunicipalMapFilters());

    act(() => {
      useMunicipalFilterStore.getState().setLayerEnabled(true);
      useMunicipalFilterStore.getState().setSource('osm');
      useMunicipalFilterStore.getState().setOccupancy('static');
      useMunicipalFilterStore.getState().setRadiusMeters(500);
      useMunicipalFilterStore.getState().resetFilters();
    });

    expect(result.current).toMatchObject({
      layerEnabled: true,
      source: 'all',
      occupancy: 'all',
      radiusMeters: 1500,
    });
  });

  it('hydration restores and sanitizes persisted values without looping', async () => {
    mockReadJson.mockResolvedValueOnce({
      version: 1,
      layerEnabled: false,
      source: 'bogus',
      occupancy: 'live',
      radiusMeters: 1000,
    });

    const { result } = renderHook(() => useMunicipalMapFilters());
    await act(async () => {
      await useMunicipalFilterStore.getState().hydrate();
    });

    expect(result.current).toMatchObject({
      layerEnabled: false,
      source: 'all',
      occupancy: 'live',
      radiusMeters: 1000,
    });
  });

  it('fails closed against the original unsafe object selector pattern', () => {
    const renders = { count: 0 };

    function UnsafeProbe() {
      // Intentionally mirrors the 921706f MapScreen subscription bug.
      useMunicipalFilterStore(unsafeSelectMunicipalMapFilters as never);
      renders.count += 1;
      const countRef = useRef(renders.count);
      countRef.current = renders.count;
      useEffect(() => {
        if (countRef.current > 50) {
          throw new Error('Maximum update depth exceeded (simulated unsafe selector)');
        }
      });
      return <Text testID="unsafe">{String(renders.count)}</Text>;
    }

    expect(() => render(<UnsafeProbe />)).toThrow(/Maximum update depth|getSnapshot/);
  });
});
