import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = join(__dirname, '..');
const SCREEN = readFileSync(join(ROOT, 'PublicExploreScreen.tsx'), 'utf8');
const MAP_HTML = readFileSync(join(__dirname, '../../map/mapHtml.ts'), 'utf8');
const MAP_SURFACE = readFileSync(join(__dirname, '../../map/MapSurface.tsx'), 'utf8');

/**
 * Wave 5 — marker semantic parity gates (presentation only).
 */
describe('MOBILE-V2-PUBLIC-DISCOVERY-W5 marker semantics', () => {
  it('wires municipal category to theme secondary green (not freshness blue)', () => {
    expect(MAP_SURFACE).toContain('municipal: theme.colors.secondary');
    expect(MAP_HTML).toContain('municipal:');
    expect(MAP_HTML).toContain('--muni-accent: ${colors.municipal}');
  });

  it('uses GREEN P glyph for municipal markers', () => {
    expect(MAP_HTML).toContain('pk-muni-p');
    expect(MAP_HTML).toContain("class=\"pk-muni-p\">P</span>");
  });

  it('uses BLUE P for community markers (primary shape is not countdown pill)', () => {
    expect(MAP_HTML).toContain('pk-spot-pin');
    expect(MAP_HTML).toContain("class=\"pk-spot-p\">P</span>");
    expect(MAP_HTML).toContain('--community-accent');
    expect(MAP_HTML).not.toMatch(/class="pk-pill"/);
    expect(MAP_HTML).not.toContain('pk-time');
  });

  it('keeps destination and user location distinct from parking P markers', () => {
    expect(MAP_HTML).toContain('.pk-dest-pin');
    expect(MAP_HTML).toContain('background: #008069');
    expect(MAP_HTML).toContain('.pk-user');
  });

  it('anonymous public Explore never pushes community spot markers', () => {
    expect(SCREEN).toContain('setSpots([])');
    expect(SCREEN).not.toMatch(/setSpots\(\s*markers/);
  });

  it('does not invent community markers from communitySpotCountInScope', () => {
    expect(SCREEN).toContain('communitySpotCountInScope');
    expect(SCREEN).not.toMatch(/communitySpotCountInScope[\s\S]{0,200}setSpots\(/);
  });
});
