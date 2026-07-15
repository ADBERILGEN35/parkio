import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const appDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const publicDir = resolve(appDir, 'public');

function readAppHtml() {
  return readFileSync(resolve(appDir, 'index.html'), 'utf8');
}

describe('landing metadata', () => {
  it('keeps launch SEO metadata aligned with parkio.dev', () => {
    const html = readAppHtml();

    expect(html).toContain('<title>Parkio</title>');
    expect(html).toContain('lang="tr"');
    expect(html).toContain('name="description"');
    expect(html).toContain('Parkio');
    expect(html).toContain('topluluk destekli park yeri');
    expect(html).toContain('park yeri bul');
    expect(html).toContain('park yeri bul, paylaş ve doğrula');
    expect(html).not.toContain('Community-powered parking for drivers');
    expect(html).toContain('Turkish-default');
    expect(html).toContain('canonical product language');
    expect(html).toContain('<link rel="canonical" href="https://parkio.dev/" />');
    expect(html).toContain('<meta name="robots" content="index, follow" />');
    expect(html).toContain('<meta property="og:url" content="https://parkio.dev/" />');
    expect(html).toContain('<meta name="theme-color" content="#0050CB" />');
    expect(html).toContain(
      '<meta property="og:image" content="https://parkio.dev/og-parkio.png" />',
    );
    expect(html).toContain('<meta name="twitter:card" content="summary_large_image" />');
    expect(html).toContain(
      '<meta name="twitter:image" content="https://parkio.dev/social-preview.png" />',
    );
    expect(html).not.toContain('Community-Powered Parking Intelligence');
  });

  it('ships parseable JSON-LD for the public landing page', () => {
    const html = readAppHtml();
    const jsonLd = html.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/)?.[1];

    expect(jsonLd).toBeTruthy();
    const parsed = JSON.parse(jsonLd ?? '{}') as {
      '@graph': Array<{ '@type': string; url?: string; description?: string }>;
    };

    expect(parsed['@graph'].map((entry) => entry['@type'])).toEqual([
      'Organization',
      'WebSite',
      'SoftwareApplication',
    ]);
    expect(parsed['@graph'].every((entry) => entry.url === 'https://parkio.dev/')).toBe(true);
    const software = parsed['@graph'].find((entry) => entry['@type'] === 'SoftwareApplication');
    expect(software?.description).toContain('park yeri bul');
  });

  it('publishes crawler entry points for the public pages only', () => {
    const robots = readFileSync(resolve(publicDir, 'robots.txt'), 'utf8');
    const sitemap = readFileSync(resolve(publicDir, 'sitemap.xml'), 'utf8');

    expect(robots).toContain('User-agent: *');
    expect(robots).toContain('Allow: /');
    expect(robots).toContain('Sitemap: https://parkio.dev/sitemap.xml');
    expect(sitemap).toContain('<loc>https://parkio.dev/</loc>');
    expect(sitemap).toContain('<loc>https://parkio.dev/privacy</loc>');
    expect(sitemap).toContain('<loc>https://parkio.dev/terms</loc>');
    expect(sitemap).not.toContain('/map');
  });
});
