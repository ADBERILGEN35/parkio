import {
  REQUIRED_GPS_ACCURACY_METERS,
  WARNING_GPS_ACCURACY_METERS,
  gpsSignalLevel,
  isGpsAccuracyAcceptable,
} from '../locationAccuracy';

describe('gpsSignalLevel', () => {
  it('buckets accuracy into user-facing levels', () => {
    expect(gpsSignalLevel(4)).toBe('excellent');
    expect(gpsSignalLevel(10)).toBe('excellent');
    expect(gpsSignalLevel(22)).toBe('good');
    expect(gpsSignalLevel(WARNING_GPS_ACCURACY_METERS)).toBe('good');
    expect(gpsSignalLevel(48)).toBe('usable');
    expect(gpsSignalLevel(REQUIRED_GPS_ACCURACY_METERS)).toBe('usable');
    expect(gpsSignalLevel(120)).toBe('poor');
  });

  it('treats missing or nonsense accuracy as none', () => {
    expect(gpsSignalLevel(null)).toBe('none');
    expect(gpsSignalLevel(undefined)).toBe('none');
    expect(gpsSignalLevel(0)).toBe('none');
    expect(gpsSignalLevel(-3)).toBe('none');
  });

  it('never reports a submittable level for accuracy the gate rejects', () => {
    // The pill and the Share button must agree: poor/none ⇔ not acceptable.
    for (const meters of [null, 0, 76, 120, 500]) {
      const level = gpsSignalLevel(meters);
      expect(isGpsAccuracyAcceptable(meters)).toBe(level !== 'poor' && level !== 'none');
    }
  });
});
