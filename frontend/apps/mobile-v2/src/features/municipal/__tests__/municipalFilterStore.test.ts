import { act } from '@testing-library/react-native';

const mockReadJson = jest.fn();
const mockWriteJson = jest.fn();

jest.mock('@/services/jsonStore', () => ({
  readJson: (...args: unknown[]) => mockReadJson(...args),
  writeJson: (...args: unknown[]) => mockWriteJson(...args),
}));

import { DEFAULT_MUNICIPAL_MAP_FILTERS } from '../municipalFilterModel';
import { useMunicipalFilterStore } from '../municipalFilterStore';

describe('municipalFilterStore', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useMunicipalFilterStore.setState({
      ...DEFAULT_MUNICIPAL_MAP_FILTERS,
      hydrated: false,
    });
  });

  it('starts from product defaults', () => {
    const state = useMunicipalFilterStore.getState();
    expect(state.layerEnabled).toBe(true);
    expect(state.source).toBe('all');
    expect(state.occupancy).toBe('all');
    expect(state.radiusMeters).toBe(1500);
  });

  it('updates source, occupancy, radius, and layer with persistence', () => {
    act(() => {
      useMunicipalFilterStore.getState().setSource('izum');
      useMunicipalFilterStore.getState().setOccupancy('live');
      useMunicipalFilterStore.getState().setRadiusMeters(3000);
      useMunicipalFilterStore.getState().setLayerEnabled(false);
    });
    const state = useMunicipalFilterStore.getState();
    expect(state.source).toBe('izum');
    expect(state.occupancy).toBe('live');
    expect(state.radiusMeters).toBe(3000);
    expect(state.layerEnabled).toBe(false);
    expect(mockWriteJson).toHaveBeenCalled();
    const last = mockWriteJson.mock.calls.at(-1)?.[1];
    expect(last).toMatchObject({
      source: 'izum',
      occupancy: 'live',
      radiusMeters: 3000,
      layerEnabled: false,
    });
  });

  it('resets filters without clearing layer visibility', () => {
    act(() => {
      useMunicipalFilterStore.getState().setLayerEnabled(true);
      useMunicipalFilterStore.getState().setSource('osm');
      useMunicipalFilterStore.getState().setOccupancy('static');
      useMunicipalFilterStore.getState().setRadiusMeters(500);
      useMunicipalFilterStore.getState().resetFilters();
    });
    const state = useMunicipalFilterStore.getState();
    expect(state.source).toBe('all');
    expect(state.occupancy).toBe('all');
    expect(state.radiusMeters).toBe(1500);
    expect(state.layerEnabled).toBe(true);
  });

  it('rehydrates and sanitizes persisted values', async () => {
    mockReadJson.mockResolvedValueOnce({
      version: 1,
      layerEnabled: false,
      source: 'bogus',
      occupancy: 'live',
      radiusMeters: 1000,
    });
    await act(async () => {
      await useMunicipalFilterStore.getState().hydrate();
    });
    const state = useMunicipalFilterStore.getState();
    expect(state.hydrated).toBe(true);
    expect(state.layerEnabled).toBe(false);
    expect(state.source).toBe('all');
    expect(state.occupancy).toBe('live');
    expect(state.radiusMeters).toBe(1000);
  });
});
