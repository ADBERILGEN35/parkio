import { act, renderHook } from '@testing-library/react-native';
import { useDebouncedValue } from '../useDebouncedValue';

describe('useDebouncedValue', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('returns the initial value immediately', () => {
    const { result } = renderHook(() => useDebouncedValue('a', 300));
    expect(result.current).toBe('a');
  });

  it('emits the latest value only after the quiet window', () => {
    const { result, rerender } = renderHook(({ v }: { v: string }) => useDebouncedValue(v, 300), {
      initialProps: { v: 'a' },
    });

    rerender({ v: 'ab' });
    rerender({ v: 'abc' });
    expect(result.current).toBe('a'); // still debouncing

    act(() => jest.advanceTimersByTime(300));
    expect(result.current).toBe('abc'); // only the final keystroke survives
  });

  it('resets the timer on each change (no premature emit)', () => {
    const { result, rerender } = renderHook(({ v }: { v: string }) => useDebouncedValue(v, 300), {
      initialProps: { v: 'a' },
    });

    rerender({ v: 'ab' });
    act(() => jest.advanceTimersByTime(200));
    rerender({ v: 'abc' });
    act(() => jest.advanceTimersByTime(200));
    expect(result.current).toBe('a'); // 400ms elapsed but never 300ms of quiet

    act(() => jest.advanceTimersByTime(100));
    expect(result.current).toBe('abc');
  });
});
