import fs from 'node:fs';
import path from 'node:path';

const mapScreen = path.join(__dirname, '../../../../app/(main)/(tabs)/map.tsx');

describe('authenticated map preservation', () => {
  it('still uses private municipal nearby and community nearby hooks', () => {
    const source = fs.readFileSync(mapScreen, 'utf8');
    expect(source).toContain('useNearbySpots');
    expect(source).toContain('useNearbyMunicipalFacilities');
    expect(source).not.toContain('publicExploreQueryOptions');
    expect(source).not.toContain('createPublicExploreApi');
  });
});
