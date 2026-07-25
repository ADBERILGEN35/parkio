import { buildParkingShareContent } from './parkingLocationLinks';

export type ShareParkingLocationResult =
  | { ok: true; method: 'share' | 'clipboard' }
  | { ok: false; reason: 'invalid_destination' | 'cancelled' | 'unavailable' };

function isShareCancellation(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const name = 'name' in error && typeof error.name === 'string' ? error.name : '';
  const message = 'message' in error && typeof error.message === 'string' ? error.message : '';
  if (name === 'AbortError') return true;
  return /cancel|dismiss|abort/i.test(message);
}

async function copyToClipboard(text: string): Promise<boolean> {
  if (typeof navigator === 'undefined') return false;
  try {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    return false;
  }
  return false;
}

/**
 * Share parked location: Web Share API first, then clipboard fallback.
 * User-cancelled native share is not treated as failure when distinguishable.
 */
export async function shareParkingLocation(options: {
  latitude: number;
  longitude: number;
  title: string;
  lead: string;
}): Promise<ShareParkingLocationResult> {
  let content: ReturnType<typeof buildParkingShareContent>;
  try {
    content = buildParkingShareContent(
      options.latitude,
      options.longitude,
      options.title,
      options.lead,
    );
  } catch {
    return { ok: false, reason: 'invalid_destination' };
  }

  const canShare =
    typeof navigator !== 'undefined' &&
    typeof navigator.share === 'function';

  if (canShare) {
    try {
      await navigator.share({
        title: content.title,
        text: content.text,
        url: content.url,
      });
      return { ok: true, method: 'share' };
    } catch (error) {
      if (isShareCancellation(error)) {
        return { ok: false, reason: 'cancelled' };
      }
      // Fall through to clipboard for non-cancel failures.
    }
  }

  const copied = await copyToClipboard(content.url);
  if (copied) {
    return { ok: true, method: 'clipboard' };
  }

  return { ok: false, reason: 'unavailable' };
}