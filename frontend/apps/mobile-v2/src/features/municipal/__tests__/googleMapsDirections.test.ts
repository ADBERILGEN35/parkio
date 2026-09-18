import {
  buildGoogleMapsDirectionsUrl,
  isTrustedGoogleMapsDirectionsUrl,
  openGoogleMapsDirections,
} from '../googleMapsDirections';

jest.mock('expo-linking', () => ({
  openURL: jest.fn(async () => undefined),
}));

const Linking = jest.requireMock('expo-linking') as {
  openURL: jest.Mock;
};

describe('googleMapsDirections', () => {
  beforeEach(() => {
    Linking.openURL.mockReset();
    Linking.openURL.mockResolvedValue(undefined);
  });

  it('builds official Google Maps directions URL without origin', () => {
    const url = buildGoogleMapsDirectionsUrl(38.4237, 27.1428);
    const parsed = new URL(url);
    expect(parsed.protocol).toBe('https:');
    expect(parsed.hostname).toBe('www.google.com');
    expect(parsed.pathname).toBe('/maps/dir/');
    expect(parsed.searchParams.get('api')).toBe('1');
    expect(parsed.searchParams.get('destination')).toBe('38.4237,27.1428');
    expect(parsed.searchParams.has('origin')).toBe(false);
    expect(isTrustedGoogleMapsDirectionsUrl(url)).toBe(true);
  });

  it('rejects invalid latitude and longitude', () => {
    expect(() => buildGoogleMapsDirectionsUrl(Number.NaN, 27)).toThrow('invalid_destination');
    expect(() => buildGoogleMapsDirectionsUrl(38, Number.NaN)).toThrow('invalid_destination');
    expect(() => buildGoogleMapsDirectionsUrl(91, 27)).toThrow('invalid_destination');
    expect(() => buildGoogleMapsDirectionsUrl(38, 181)).toThrow('invalid_destination');
  });

  it('never treats arbitrary external URLs as trusted', () => {
    expect(isTrustedGoogleMapsDirectionsUrl('https://www.openstreetmap.org/?mlat=1&mlon=2')).toBe(
      false,
    );
    expect(isTrustedGoogleMapsDirectionsUrl('https://evil.example/maps/dir/?api=1&destination=1,2')).toBe(
      false,
    );
    expect(isTrustedGoogleMapsDirectionsUrl('javascript:alert(1)')).toBe(false);
    expect(
      isTrustedGoogleMapsDirectionsUrl(
        'https://www.google.com/maps/dir/?api=1&destination=not-a-coord',
      ),
    ).toBe(false);
  });

  it('opens Linking with reconstructed trusted URL only', async () => {
    await expect(openGoogleMapsDirections(38.4237, 27.1428)).resolves.toBe('opened');
    expect(Linking.openURL).toHaveBeenCalledTimes(1);
    const url = Linking.openURL.mock.calls[0][0] as string;
    expect(url).toContain('https://www.google.com/maps/dir/');
    expect(url).toContain('api=1');
    expect(url).toContain('destination=');
  });

  it('does not call Linking for invalid coordinates', async () => {
    await expect(openGoogleMapsDirections(Number.NaN, 27)).resolves.toBe('invalid');
    await expect(openGoogleMapsDirections(38, 200)).resolves.toBe('invalid');
    expect(Linking.openURL).not.toHaveBeenCalled();
  });

  it('reports failed when Linking.openURL rejects', async () => {
    Linking.openURL.mockRejectedValueOnce(new Error('blocked'));
    await expect(openGoogleMapsDirections(38.4237, 27.1428)).resolves.toBe('failed');
  });
});
