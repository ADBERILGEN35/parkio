import {
  buildParkingMapsHttpsUrl,
  buildParkingNavigationUrl,
  buildParkingShareContent,
  formatParkingCoordinate,
  isValidParkingDestination,
  requireParkingDestination,
} from '../parkingLocationLinks';

describe('parkingLocationLinks', () => {
  it('formats coordinates with locale-independent dots', () => {
    expect(formatParkingCoordinate(41.0082)).toBe('41.0082');
    expect(formatParkingCoordinate(-28.9784)).toBe('-28.9784');
    expect(formatParkingCoordinate(0)).toBe('0');
  });

  it('accepts boundary and negative destinations', () => {
    expect(isValidParkingDestination(90, 180)).toBe(true);
    expect(isValidParkingDestination(-90, -180)).toBe(true);
    expect(isValidParkingDestination(0, 0)).toBe(true);
    expect(requireParkingDestination(-33.8688, 151.2093)).toEqual({
      latitude: -33.8688,
      longitude: 151.2093,
    });
  });

  it('rejects invalid destinations', () => {
    expect(isValidParkingDestination(91, 0)).toBe(false);
    expect(isValidParkingDestination(0, 181)).toBe(false);
    expect(isValidParkingDestination(Number.NaN, 0)).toBe(false);
    expect(isValidParkingDestination(0, Number.POSITIVE_INFINITY)).toBe(false);
    expect(isValidParkingDestination('41' as never, 28)).toBe(false);
    expect(() => requireParkingDestination(Number.NaN, 0)).toThrow('invalid_destination');
  });

  it('builds platform navigation URLs without session metadata', () => {
    const ios = buildParkingNavigationUrl(41.0082, 28.9784, 'ios', 'Park yerim');
    const android = buildParkingNavigationUrl(41.0082, 28.9784, 'android', 'Park yerim');
    const fallback = buildParkingNavigationUrl(41.0082, 28.9784, 'default');

    expect(ios).toBe('maps://?daddr=41.0082,28.9784&q=Park%20yerim');
    expect(android).toBe('geo:41.0082,28.9784?q=41.0082,28.9784(Park%20yerim)');
    expect(fallback).toContain('openstreetmap.org');
    expect(fallback).toContain('mlat=41.0082');
    expect(fallback).toContain('mlon=28.9784');

    for (const url of [ios, android, fallback]) {
      expect(url).not.toMatch(/session/i);
      expect(url).not.toMatch(/user/i);
      expect(url).not.toMatch(/token/i);
      expect(url).not.toMatch(/ACTIVE|MANUAL|idempotency/i);
    }
  });

  it('builds share content with localized lead and https maps link only', () => {
    const content = buildParkingShareContent(41.0082, 28.9784, 'Park konumum:');
    expect(content.message).toContain('Park konumum:');
    expect(content.message).toContain(content.url);
    expect(content.url).toMatch(/^https:\/\/www\.openstreetmap\.org\//);
    expect(content.message).not.toMatch(/session|userId|startedAt|ACTIVE|idempotency/i);
  });

  it('rejects share/nav builders for invalid destination', () => {
    expect(() => buildParkingMapsHttpsUrl(Number.NaN, 0)).toThrow('invalid_destination');
    expect(() => buildParkingShareContent(91, 0, 'x')).toThrow('invalid_destination');
  });
});