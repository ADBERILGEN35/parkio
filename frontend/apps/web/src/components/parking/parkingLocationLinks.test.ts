import { describe, expect, it } from 'vitest';
import {
  buildParkingMapsHttpsUrl,
  buildParkingShareContent,
  formatParkingCoordinate,
} from './parkingLocationLinks';

const SESSION_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

describe('parkingLocationLinks (web)', () => {
  it('formats coordinates with locale-independent dots', () => {
    expect(formatParkingCoordinate(41.0082)).toBe('41.0082');
    expect(formatParkingCoordinate(-28.9784)).toBe('-28.9784');
    expect(formatParkingCoordinate(0)).toBe('0');
  });

  it('builds an HTTPS OSM URL without session or user identifiers', () => {
    const url = buildParkingMapsHttpsUrl(38.42, 27.14);
    expect(url).toMatch(/^https:\/\/www\.openstreetmap\.org\//);
    expect(url).toContain('mlat=38.42');
    expect(url).toContain('mlon=27.14');
    expect(url).not.toContain(SESSION_ID);
    expect(url).not.toMatch(/session|user|token|ACTIVE|idempotency/i);
  });

  it('rejects invalid coordinates', () => {
    expect(() => buildParkingMapsHttpsUrl(Number.NaN, 27)).toThrow('invalid_destination');
    expect(() => buildParkingMapsHttpsUrl(91, 0)).toThrow('invalid_destination');
    expect(() => buildParkingShareContent(0, 181, 't', 'l')).toThrow('invalid_destination');
  });

  it('builds share content with title, lead, and maps URL only', () => {
    const content = buildParkingShareContent(38.42, 27.14, 'Parked location', 'My parking location:');
    expect(content.title).toBe('Parked location');
    expect(content.text).toContain('My parking location:');
    expect(content.text).toContain(content.url);
    expect(content.url).toMatch(/^https:\/\/www\.openstreetmap\.org\//);
    expect(content.text).not.toContain(SESSION_ID);
    expect(content.text).not.toMatch(/userId|startedAt|ACTIVE|idempotency/i);
  });
});