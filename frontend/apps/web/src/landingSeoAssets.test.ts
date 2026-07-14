import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const appDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const publicDir = resolve(appDir, 'public');

describe('landing SEO assets', () => {
  it('ships production landing metadata in index.html', () => {
    const indexHtml = readFileSync(resolve(appDir, 'index.html'), 'utf8');

    expect(indexHtml).toContain('Parkio — topluluk destekli park yeri');
    expect(indexHtml).toContain('lang="tr"');
    expect(indexHtml).toContain('https://parkio.dev/');
    expect(indexHtml).toContain('og:image');
    expect(indexHtml).toContain('twitter:card');
    expect(indexHtml).toContain('application/ld+json');
    expect(indexHtml).not.toContain('aggregateRating');
  });

  it('ships robots and sitemap for parkio.dev', () => {
    const robots = readFileSync(resolve(publicDir, 'robots.txt'), 'utf8');
    const sitemap = readFileSync(resolve(publicDir, 'sitemap.xml'), 'utf8');

    expect(robots).toContain('Allow: /');
    expect(robots).toContain('https://parkio.dev/sitemap.xml');
    expect(sitemap).toContain('<loc>https://parkio.dev/</loc>');
  });
});

