import {
  decodeMapBridgeMessage,
  MAP_BRIDGE_MAX_MESSAGE_BYTES,
} from '../mapBridgeDecode';

describe('decodeMapBridgeMessage (PA-04)', () => {
  it('BRIDGE-A: accepts valid ready', () => {
    const result = decodeMapBridgeMessage(JSON.stringify({ type: 'ready' }));
    expect(result).toEqual({ ok: true, message: { type: 'ready' } });
  });

  it('BRIDGE-A: accepts valid moveEnd at boundary coords', () => {
    const result = decodeMapBridgeMessage(
      JSON.stringify({
        type: 'moveEnd',
        lat: 90,
        lng: -180,
        zoom: 12,
        byGesture: true,
      }),
    );
    expect(result.ok).toBe(true);
  });

  it('BRIDGE-B: rejects JSON null', () => {
    expect(decodeMapBridgeMessage('null')).toEqual({
      ok: false,
      reason: 'not_object',
    });
  });

  it('BRIDGE-C: rejects arrays', () => {
    expect(decodeMapBridgeMessage('[]')).toEqual({
      ok: false,
      reason: 'not_object',
    });
  });

  it('BRIDGE-D: rejects primitives', () => {
    expect(decodeMapBridgeMessage('true').ok).toBe(false);
    expect(decodeMapBridgeMessage('42').ok).toBe(false);
    expect(decodeMapBridgeMessage('"ready"').ok).toBe(false);
  });

  it('BRIDGE-E: rejects unknown type', () => {
    expect(decodeMapBridgeMessage(JSON.stringify({ type: 'navigate' }))).toEqual({
      ok: false,
      reason: 'unknown_type',
    });
  });

  it('BRIDGE-F: rejects missing required fields', () => {
    expect(decodeMapBridgeMessage(JSON.stringify({ type: 'spotTap' }))).toEqual({
      ok: false,
      reason: 'invalid_fields',
    });
    expect(
      decodeMapBridgeMessage(JSON.stringify({ type: 'moveEnd', lat: 1, lng: 2 })),
    ).toEqual({ ok: false, reason: 'invalid_camera' });
  });

  it('BRIDGE-G/H: rejects invalid latitude', () => {
    expect(
      decodeMapBridgeMessage(JSON.stringify({ type: 'move', lat: 91, lng: 0 })),
    ).toEqual({ ok: false, reason: 'invalid_coordinates' });
    expect(
      decodeMapBridgeMessage(JSON.stringify({ type: 'move', lat: -91, lng: 0 })),
    ).toEqual({ ok: false, reason: 'invalid_coordinates' });
  });

  it('BRIDGE-I/J: rejects invalid longitude', () => {
    expect(
      decodeMapBridgeMessage(JSON.stringify({ type: 'move', lat: 0, lng: 181 })),
    ).toEqual({ ok: false, reason: 'invalid_coordinates' });
    expect(
      decodeMapBridgeMessage(JSON.stringify({ type: 'move', lat: 0, lng: -181 })),
    ).toEqual({ ok: false, reason: 'invalid_coordinates' });
  });

  it('BRIDGE-K/L: rejects NaN and Infinity', () => {
    expect(
      decodeMapBridgeMessage('{"type":"move","lat":null,"lng":0}'),
    ).toEqual({ ok: false, reason: 'invalid_coordinates' });
    // JSON has no Infinity/NaN literals — numeric strings must not coerce.
    expect(
      decodeMapBridgeMessage(JSON.stringify({ type: 'move', lat: '1', lng: '2' })),
    ).toEqual({ ok: false, reason: 'invalid_coordinates' });
  });

  it('BRIDGE-M: rejects oversized payload before parse', () => {
    const oversized = `{"type":"ready","pad":"${'x'.repeat(MAP_BRIDGE_MAX_MESSAGE_BYTES)}"}`;
    expect(decodeMapBridgeMessage(oversized)).toEqual({
      ok: false,
      reason: 'oversized',
    });
  });

  it('BRIDGE-N: accepts valid boundary coordinates', () => {
    const result = decodeMapBridgeMessage(
      JSON.stringify({ type: 'move', lat: -90, lng: 180 }),
    );
    expect(result).toEqual({
      ok: true,
      message: { type: 'move', lat: -90, lng: 180 },
    });
  });

  it('BRIDGE-O: rejects invalid zoom', () => {
    expect(
      decodeMapBridgeMessage(
        JSON.stringify({
          type: 'moveEnd',
          lat: 0,
          lng: 0,
          zoom: 99,
          byGesture: false,
        }),
      ),
    ).toEqual({ ok: false, reason: 'invalid_camera' });
  });

  it('rejects non-string raw input without throwing', () => {
    expect(decodeMapBridgeMessage(null as unknown as string)).toEqual({
      ok: false,
      reason: 'not_string',
    });
    expect(decodeMapBridgeMessage(undefined as unknown as string)).toEqual({
      ok: false,
      reason: 'not_string',
    });
  });
});
