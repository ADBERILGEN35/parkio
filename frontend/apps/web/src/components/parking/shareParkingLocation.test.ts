import { afterEach, describe, expect, it, vi } from 'vitest';
import { shareParkingLocation } from './shareParkingLocation';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('shareParkingLocation', () => {
  it('uses the Web Share API when available', async () => {
    const share = vi.fn(async () => undefined);
    vi.stubGlobal('navigator', { share });

    await expect(
      shareParkingLocation({
        latitude: 38.42,
        longitude: 27.14,
        title: 'Parked location',
        lead: 'My parking location:',
      }),
    ).resolves.toEqual({ ok: true, method: 'share' });

    expect(share).toHaveBeenCalledOnce();
    const payload = share.mock.calls[0]![0] as { url: string; text: string };
    expect(payload.url).toMatch(/^https:\/\/www\.openstreetmap\.org\//);
    expect(payload.url).not.toMatch(/aaaaaaaa|session|user/i);
  });

  it('treats AbortError as cancellation without clipboard fallback', async () => {
    const share = vi.fn(async () => {
      const err = new Error('Share canceled');
      err.name = 'AbortError';
      throw err;
    });
    const writeText = vi.fn();
    vi.stubGlobal('navigator', { share, clipboard: { writeText } });

    await expect(
      shareParkingLocation({
        latitude: 38.42,
        longitude: 27.14,
        title: 'Parked location',
        lead: 'My parking location:',
      }),
    ).resolves.toEqual({ ok: false, reason: 'cancelled' });
    expect(writeText).not.toHaveBeenCalled();
  });

  it('falls back to clipboard when share is unavailable', async () => {
    const writeText = vi.fn(async () => undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });

    await expect(
      shareParkingLocation({
        latitude: 38.42,
        longitude: 27.14,
        title: 'Parked location',
        lead: 'My parking location:',
      }),
    ).resolves.toEqual({ ok: true, method: 'clipboard' });

    expect(writeText).toHaveBeenCalledOnce();
    expect(writeText.mock.calls[0]![0]).toMatch(/^https:\/\/www\.openstreetmap\.org\//);
  });

  it('returns unavailable when clipboard also fails', async () => {
    const writeText = vi.fn(async () => {
      throw new Error('denied');
    });
    vi.stubGlobal('navigator', { clipboard: { writeText } });

    await expect(
      shareParkingLocation({
        latitude: 38.42,
        longitude: 27.14,
        title: 'Parked location',
        lead: 'My parking location:',
      }),
    ).resolves.toEqual({ ok: false, reason: 'unavailable' });
  });

  it('rejects invalid coordinates without sharing', async () => {
    const share = vi.fn();
    vi.stubGlobal('navigator', { share });
    await expect(
      shareParkingLocation({
        latitude: Number.NaN,
        longitude: 27.14,
        title: 'Parked location',
        lead: 'My parking location:',
      }),
    ).resolves.toEqual({ ok: false, reason: 'invalid_destination' });
    expect(share).not.toHaveBeenCalled();
  });
});