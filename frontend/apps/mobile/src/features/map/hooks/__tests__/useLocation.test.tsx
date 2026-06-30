import { act, renderHook, waitFor } from '@testing-library/react-native';
import * as Location from 'expo-location';
import { useLocation } from '../useLocation';

jest.mock('expo-location', () => ({
  Accuracy: { Balanced: 3 },
  getForegroundPermissionsAsync: jest.fn(),
  requestForegroundPermissionsAsync: jest.fn(),
  getCurrentPositionAsync: jest.fn(),
  getLastKnownPositionAsync: jest.fn(),
}));

const L = Location as jest.Mocked<typeof Location>;

const granted = { status: 'granted', canAskAgain: true } as Location.LocationPermissionResponse;
const undetermined = { status: 'undetermined', canAskAgain: true } as Location.LocationPermissionResponse;
const blocked = { status: 'denied', canAskAgain: false } as Location.LocationPermissionResponse;
const deniedRetryable = { status: 'denied', canAskAgain: true } as Location.LocationPermissionResponse;

const fix = { coords: { latitude: 38.4187, longitude: 27.1283 } } as Location.LocationObject;

describe('useLocation', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    L.getForegroundPermissionsAsync.mockResolvedValue(undetermined);
    L.getCurrentPositionAsync.mockResolvedValue(fix);
    L.getLastKnownPositionAsync.mockResolvedValue(null);
  });

  it('reflects a previously-granted permission on mount without prompting', async () => {
    L.getForegroundPermissionsAsync.mockResolvedValue(granted);
    const { result } = renderHook(() => useLocation());

    await waitFor(() => expect(result.current.permission).toBe('granted'));
    expect(L.requestForegroundPermissionsAsync).not.toHaveBeenCalled();
  });

  it('requests permission and returns the fix when granted', async () => {
    L.requestForegroundPermissionsAsync.mockResolvedValue(granted);
    const { result } = renderHook(() => useLocation());
    await waitFor(() => expect(result.current.permission).toBe('undetermined'));

    let coords: unknown;
    await act(async () => {
      coords = await result.current.request();
    });

    expect(coords).toEqual({ lat: 38.4187, lng: 27.1283 });
    expect(result.current.permission).toBe('granted');
    expect(result.current.location).toEqual({ lat: 38.4187, lng: 27.1283 });
  });

  it('maps "deny + don\'t ask again" to the blocked state', async () => {
    L.requestForegroundPermissionsAsync.mockResolvedValue(blocked);
    const { result } = renderHook(() => useLocation());

    await act(async () => {
      await result.current.request();
    });

    expect(result.current.permission).toBe('blocked');
    expect(result.current.location).toBeNull();
    expect(L.getCurrentPositionAsync).not.toHaveBeenCalled();
  });

  it('maps a retryable denial to the denied state', async () => {
    L.requestForegroundPermissionsAsync.mockResolvedValue(deniedRetryable);
    const { result } = renderHook(() => useLocation());

    await act(async () => {
      await result.current.request();
    });

    expect(result.current.permission).toBe('denied');
  });

  it('falls back to the last known position when a live fix fails', async () => {
    L.requestForegroundPermissionsAsync.mockResolvedValue(granted);
    L.getCurrentPositionAsync.mockRejectedValue(new Error('timeout'));
    L.getLastKnownPositionAsync.mockResolvedValue({
      coords: { latitude: 10, longitude: 20 },
    } as Location.LocationObject);

    const { result } = renderHook(() => useLocation());
    let coords: unknown;
    await act(async () => {
      coords = await result.current.request();
    });

    expect(coords).toEqual({ lat: 10, lng: 20 });
  });

  it('surfaces a recoverable error (never throws) when no fix is available', async () => {
    L.requestForegroundPermissionsAsync.mockResolvedValue(granted);
    L.getCurrentPositionAsync.mockRejectedValue(new Error('timeout'));
    L.getLastKnownPositionAsync.mockResolvedValue(null);

    const { result } = renderHook(() => useLocation());
    let coords: unknown = 'unset';
    await act(async () => {
      coords = await result.current.request();
    });

    expect(coords).toBeNull();
    expect(result.current.error).toMatch(/location/i);
  });
});
