import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildMapHtml, MAPLIBRE_VERSION } from '../mapHtml';
import {
  MAPLIBRE_RUNTIME_CSS,
  MAPLIBRE_RUNTIME_CSS_SHA256,
  MAPLIBRE_RUNTIME_JS,
  MAPLIBRE_RUNTIME_JS_SHA256,
  MAPLIBRE_RUNTIME_PACKAGE_INTEGRITY,
  MAPLIBRE_RUNTIME_VERSION,
} from '../vendor/maplibreRuntime.generated';

const colors = {
  fresh: '#0050CB',
  aging: '#A06500',
  expiring: '#BA1A1A',
  track: '#DCE9FF',
  pillBg: '#FFFFFF',
  pillText: '#0B1C30',
  muted: '#727687',
  primary: '#0050CB',
  userDot: '#0050CB',
  userHalo: 'rgba(0,80,203,0.18)',
  municipal: '#006C49',
  municipalGlyph: '#FFFFFF',
  communityGlyph: '#FFFFFF',
};

describe('MapLibre local runtime provenance (PA-05)', () => {
  const vendorDir = join(__dirname, '../vendor');
  const manifest = JSON.parse(
    readFileSync(join(vendorDir, 'maplibre-runtime.manifest.json'), 'utf8'),
  ) as {
    version: string;
    packageIntegrity: string;
    jsSha256: string;
    cssSha256: string;
    runtimeMode: string;
  };

  it('pins exact MapLibre 4.7.1 with official integrity', () => {
    expect(MAPLIBRE_VERSION).toBe('4.7.1');
    expect(MAPLIBRE_RUNTIME_VERSION).toBe('4.7.1');
    expect(manifest.version).toBe('4.7.1');
    expect(manifest.runtimeMode).toBe('bundled');
    expect(MAPLIBRE_RUNTIME_PACKAGE_INTEGRITY).toBe(manifest.packageIntegrity);
    expect(manifest.packageIntegrity).toMatch(/^sha512-/);
  });

  it('verifies JS/CSS SHA-256 against manifest and binary copies', () => {
    const jsFile = readFileSync(join(vendorDir, 'maplibre-gl.js'));
    const cssFile = readFileSync(join(vendorDir, 'maplibre-gl.css'));
    const jsHash = createHash('sha256').update(jsFile).digest('hex');
    const cssHash = createHash('sha256').update(cssFile).digest('hex');

    expect(jsHash).toBe(manifest.jsSha256);
    expect(cssHash).toBe(manifest.cssSha256);
    expect(jsHash).toBe(MAPLIBRE_RUNTIME_JS_SHA256);
    expect(cssHash).toBe(MAPLIBRE_RUNTIME_CSS_SHA256);
    expect(createHash('sha256').update(MAPLIBRE_RUNTIME_JS, 'utf8').digest('hex')).toBe(
      jsHash,
    );
    expect(createHash('sha256').update(MAPLIBRE_RUNTIME_CSS, 'utf8').digest('hex')).toBe(
      cssHash,
    );
  });

  it('embeds local runtime and contains no executable CDN hosts', () => {
    const html = buildMapHtml({
      center: { lat: 38.42, lng: 27.14 },
      zoom: 12,
      mode: 'light',
      colors,
    });

    expect(html).not.toMatch(/unpkg\.com/i);
    expect(html).not.toMatch(/jsdelivr\.(net|com)/i);
    expect(html).not.toMatch(/cdnjs\.cloudflare\.com/i);
    expect(html).not.toMatch(/<script[^>]+src=["']https?:/i);
    expect(html).not.toMatch(/<link[^>]+href=["']https?:[^>]*(maplibre|unpkg|jsdelivr|cdnjs)/i);
    expect(html).toContain('<style>');
    expect(html).toContain('<script>');
    expect(html).toContain('maplibregl');
    expect(html).toContain('Content-Security-Policy');
    // Packaged runtime is present (UMD factory / maplibregl global).
    expect(html.includes(MAPLIBRE_RUNTIME_JS.slice(0, 80))).toBe(true);
    expect(html).toContain('© OpenStreetMap contributors');
  });

  it('does not fall back to remote MapLibre when CDN hosts are mentioned in policy tests', () => {
    const blocked = ['unpkg.com', 'jsdelivr.net', 'cdnjs.cloudflare.com'];
    const html = buildMapHtml({
      center: { lat: 38.42, lng: 27.14 },
      zoom: 12,
      mode: 'light',
      colors,
    });
    for (const host of blocked) {
      expect(html.toLowerCase()).not.toContain(host);
    }
    // Local UMD still defines maplibregl — shell can initialize without CDN JS.
    expect(MAPLIBRE_RUNTIME_JS).toMatch(/maplibregl|maplibre/i);
  });
});
