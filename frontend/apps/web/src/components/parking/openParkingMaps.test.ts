import { afterEach, describe, expect, it, vi } from 'vitest';
import { openParkingLocationInMaps } from './openParkingMaps';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('openParkingLocationInMaps', () => {
  it('opens a safe HTTPS maps URL with noopener features', () => {
    const opened = { opener: {} as Window | null };
    const open = vi.fn(() => opened as unknown as Window);
    vi.stubGlobal('open', open);

    expect(openParkingLocationInMaps(38.42, 27.14)).toBe(true);
    expect(open).toHaveBeenCalledOnce();
    const [url, target, features] = open.mock.calls[0]!;
    expect(url).toMatch(/^https:\/\/www\.openstreetmap\.org\//);
    expect(url).not.toMatch(/aaaaaaaa-aaaa|session|user/i);
    expect(target).toBe('_blank');
    expect(features).toContain('noopener');
    expect(features).toContain('noreferrer');
    expect(opened.opener).toBeNull();
  });

  it('returns false for invalid coordinates without opening', () => {
    const open = vi.fn();
    vi.stubGlobal('open', open);
    expect(openParkingLocationInMaps(Number.NaN, 27.14)).toBe(false);
    expect(open).not.toHaveBeenCalled();
  });

  it('returns false when the browser blocks the popup', () => {
    vi.stubGlobal('open', vi.fn(() => null));
    expect(openParkingLocationInMaps(38.42, 27.14)).toBe(false);
  });
});