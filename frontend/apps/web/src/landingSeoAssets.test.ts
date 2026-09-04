import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const appDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const publicDir = resolve(appDir, 'public');

describe('app SEO assets', () => {
  it('keeps account application metadata non-indexable and correctly scoped', () => {
    const indexHtml = readFileSync(resolve(appDir, 'index.html'), 'utf8');

    expect(indexHtml).toContain('<title>Parkio App</title>');
    expect(indexHtml).toContain('lang="en"');
    expect(indexHtml).toContain('content="noindex,follow"');
    expect(indexHtml).toContain('https://app.parkio.dev/');
    expect(indexHtml).toContain('og:image');
    expect(indexHtml).toContain('twitter:card');
    expect(indexHtml).toContain('application/ld+json');
    expect(indexHtml).not.toContain('aggregateRating');
  });

  it('ships robots and a single-entry sitemap for public explore only', () => {
    const robots = readFileSync(resolve(publicDir, 'robots.txt'), 'utf8');
    const sitemap = readFileSync(resolve(publicDir, 'sitemap.xml'), 'utf8');

    expect(robots).toContain('Allow: /explore');
    expect(robots).toContain('https://app.parkio.dev/sitemap.xml');
    expect(sitemap).toContain('<loc>https://app.parkio.dev/explore</loc>');
    expect(sitemap.match(/<loc>/g)).toHaveLength(1);
  });
});
