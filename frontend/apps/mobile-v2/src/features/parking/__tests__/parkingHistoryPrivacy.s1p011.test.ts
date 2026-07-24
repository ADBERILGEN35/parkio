import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(__dirname, '..');

const HISTORY_FILES = [
  'useParkingSessionHistory.ts',
  'parkingHistoryModel.ts',
  'ParkingSessionHistoryRow.tsx',
  path.join('__tests__', 'useParkingSessionHistory.s1p011.test.tsx'),
  path.join('__tests__', 'ParkingHistoryScreen.s1p011.test.tsx'),
  path.join('__tests__', 'parkingHistoryModel.test.ts'),
];

const FORBIDDEN = [
  'AsyncStorage',
  'SecureStore',
  'jsonStore',
  'expo-sqlite',
  'SQLite',
  'parking_history_deleted',
  'parking_session_deleted',
  'trackProductEvent',
  'productAnalytics',
];

describe('S1-P0-11 parking history privacy guards', () => {
  it('does not introduce persistence, deletion analytics, or coordinate product events', () => {
    for (const rel of HISTORY_FILES) {
      const full = path.join(ROOT, rel);
      const source = fs.readFileSync(full, 'utf8');
      for (const token of FORBIDDEN) {
        expect(source.includes(token)).toBe(false);
      }
      expect(source).not.toMatch(/console\.(log|info|debug|warn)\([^)]*latitude/i);
      expect(source).not.toMatch(/console\.(log|info|debug|warn)\([^)]*longitude/i);
    }
  });

  it('screen route stays nested under profile (no new tab)', () => {
    const route = path.resolve(__dirname, '../../../../app/(main)/profile/parking-history.tsx');
    expect(fs.existsSync(route)).toBe(true);
    const tabs = path.resolve(__dirname, '../../../../app/(main)/(tabs)');
    const tabFiles = fs.readdirSync(tabs);
    expect(tabFiles.some((name) => name.toLowerCase().includes('history'))).toBe(false);
  });
});