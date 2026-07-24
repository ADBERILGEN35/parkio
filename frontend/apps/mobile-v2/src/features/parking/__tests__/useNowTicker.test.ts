import { act, renderHook } from '@testing-library/react-native';
import { AppState } from 'react-native';
import { useNowTicker } from '../useNowTicker';

describe('useNowTicker', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-07-21T09:00:00.000Z'));
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('recalculates from Date.now on ticks without accumulated drift', () => {
    const { result } = renderHook(() => useNowTicker(true, 1000));
    expect(result.current).toBe(Date.parse('2026-07-21T09:00:00.000Z'));

    act(() => {
      jest.setSystemTime(new Date('2026-07-21T09:00:05.000Z'));
      jest.advanceTimersByTime(1000);
    });
    expect(result.current).toBe(Date.now());
    expect(result.current).toBe(Date.parse('2026-07-21T09:00:06.000Z'));

    act(() => {
      jest.setSystemTime(new Date('2026-07-21T09:10:00.000Z'));
      jest.advanceTimersByTime(1000);
    });
    expect(result.current).toBe(Date.now());
    expect(result.current).toBe(Date.parse('2026-07-21T09:10:01.000Z'));
  });

  it('recalculates on AppState active without network', () => {
    const listeners: ((s: string) => void)[] = [];
    const addSpy = jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, cb) => {
      listeners.push(cb as (s: string) => void);
      return { remove: jest.fn() } as never;
    });

    const { result, unmount } = renderHook(() => useNowTicker(true, 1000));
    act(() => {
      jest.setSystemTime(new Date('2026-07-21T09:05:00.000Z'));
      listeners.forEach((cb) => cb('active'));
    });
    expect(result.current).toBe(Date.parse('2026-07-21T09:05:00.000Z'));

    unmount();
    expect(addSpy).toHaveBeenCalled();
    addSpy.mockRestore();
  });

  it('does not schedule when disabled', () => {
    const spy = jest.spyOn(global, 'setInterval');
    const { result } = renderHook(() => useNowTicker(false, 1000));
    expect(spy).not.toHaveBeenCalled();
    expect(typeof result.current).toBe('number');
    spy.mockRestore();
  });
});