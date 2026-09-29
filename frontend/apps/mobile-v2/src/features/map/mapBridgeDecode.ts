/**
 * Runtime decoder for WebView → React Native map bridge messages.
 * TypeScript types follow validation; they are not the security boundary.
 */

export const MAP_BRIDGE_MAX_MESSAGE_BYTES = 8_192;

/** Marker/facility ids and error codes — bounded opaque strings. */
export const MAP_BRIDGE_MAX_ID_LENGTH = 128;
export const MAP_BRIDGE_MAX_ERROR_CODE_LENGTH = 64;

/** MapLibre-compatible camera zoom range used by this app. */
export const MAP_BRIDGE_ZOOM_MIN = 0;
export const MAP_BRIDGE_ZOOM_MAX = 22;

export type MapBridgeRejectReason =
  | 'not_string'
  | 'oversized'
  | 'invalid_json'
  | 'not_object'
  | 'unknown_type'
  | 'invalid_fields'
  | 'invalid_coordinates'
  | 'invalid_camera';

export type ValidatedMapBridgeMessage =
  | { type: 'ready' }
  | { type: 'boot' }
  | { type: 'mapTap' }
  | { type: 'spotTap'; id: string }
  | { type: 'municipalTap'; id: string }
  | { type: 'move'; lat: number; lng: number }
  | {
      type: 'moveEnd';
      lat: number;
      lng: number;
      zoom: number;
      byGesture: boolean;
    }
  | { type: 'error'; code: string }
  | { type: 'debug'; message: string };

export type DecodeMapBridgeResult =
  | { ok: true; message: ValidatedMapBridgeMessage }
  | { ok: false; reason: MapBridgeRejectReason };

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isValidLat(value: unknown): value is number {
  return isFiniteNumber(value) && value >= -90 && value <= 90;
}

function isValidLng(value: unknown): value is number {
  return isFiniteNumber(value) && value >= -180 && value <= 180;
}

function isValidZoom(value: unknown): value is number {
  return (
    isFiniteNumber(value) &&
    value >= MAP_BRIDGE_ZOOM_MIN &&
    value <= MAP_BRIDGE_ZOOM_MAX
  );
}

function isBoundedId(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= MAP_BRIDGE_MAX_ID_LENGTH
  );
}

/**
 * Decode untrusted WebView `nativeEvent.data` into a validated bridge message.
 * Oversized payloads are rejected before JSON.parse.
 */
export function decodeMapBridgeMessage(raw: unknown): DecodeMapBridgeResult {
  if (typeof raw !== 'string') {
    return { ok: false, reason: 'not_string' };
  }
  // UTF-16 length approximates attacker-controlled payload size without Buffer.
  if (raw.length > MAP_BRIDGE_MAX_MESSAGE_BYTES) {
    return { ok: false, reason: 'oversized' };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    return { ok: false, reason: 'invalid_json' };
  }

  if (!isPlainObject(parsed)) {
    return { ok: false, reason: 'not_object' };
  }

  const type = parsed.type;
  if (typeof type !== 'string') {
    return { ok: false, reason: 'unknown_type' };
  }

  switch (type) {
    case 'ready':
    case 'boot':
    case 'mapTap':
      return { ok: true, message: { type } };

    case 'spotTap':
    case 'municipalTap':
      if (!isBoundedId(parsed.id)) {
        return { ok: false, reason: 'invalid_fields' };
      }
      return { ok: true, message: { type, id: parsed.id } };

    case 'move':
      if (!isValidLat(parsed.lat) || !isValidLng(parsed.lng)) {
        return { ok: false, reason: 'invalid_coordinates' };
      }
      return { ok: true, message: { type: 'move', lat: parsed.lat, lng: parsed.lng } };

    case 'moveEnd': {
      if (!isValidLat(parsed.lat) || !isValidLng(parsed.lng)) {
        return { ok: false, reason: 'invalid_coordinates' };
      }
      if (!isValidZoom(parsed.zoom)) {
        return { ok: false, reason: 'invalid_camera' };
      }
      if (typeof parsed.byGesture !== 'boolean') {
        return { ok: false, reason: 'invalid_fields' };
      }
      return {
        ok: true,
        message: {
          type: 'moveEnd',
          lat: parsed.lat,
          lng: parsed.lng,
          zoom: parsed.zoom,
          byGesture: parsed.byGesture,
        },
      };
    }

    case 'error': {
      if (
        typeof parsed.code !== 'string' ||
        parsed.code.length === 0 ||
        parsed.code.length > MAP_BRIDGE_MAX_ERROR_CODE_LENGTH
      ) {
        return { ok: false, reason: 'invalid_fields' };
      }
      return { ok: true, message: { type: 'error', code: parsed.code } };
    }

    case 'debug': {
      if (typeof parsed.message !== 'string' || parsed.message.length > 512) {
        return { ok: false, reason: 'invalid_fields' };
      }
      return { ok: true, message: { type: 'debug', message: parsed.message } };
    }

    default:
      return { ok: false, reason: 'unknown_type' };
  }
}
