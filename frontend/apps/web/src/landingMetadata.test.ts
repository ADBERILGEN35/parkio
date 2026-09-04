import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const appDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const publicDir = resolve(appDir, 'public');

function readHtml(path = 'index.html') {
  return readFileSync(resolve(appDir, path), 'utf8');
}

describe('app metadata and crawler surface', () => {
  it('keeps the authenticated app root out of the index and uses reachable app assets', () => {
    const html = readHtml();

    expect(html).toContain('<title>Parkio App</title>');
    expect(html).toContain('lang="en"');
    expect(html).toContain('<link rel="canonical" href="https://app.parkio.dev/" />');
    expect(html).toContain('<meta name="robots" content="noindex,follow" />');
    expect(html).toContain('<meta property="og:url" content="https://app.parkio.dev/" />');
    expect(html).toContain(
      '<meta property="og:image" content="https://app.parkio.dev/og-parkio.png" />',
    );
    expect(html).toContain(
      '<meta name="twitter:image" content="https://app.parkio.dev/social-preview.png" />',
    );
    expect(html).not.toContain('Founder');
    expect(html).not.toContain('sameAs');
  });

  it('ships meaningful static explore HTML without a facility snapshot', () => {
    const html = readHtml('explore/index.html');

    expect(html).toContain('<title>Parkio — Live Public Parking Explore</title>');
    expect(html).toContain('<link rel="canonical" href="https://app.parkio.dev/explore" />');
    expect(html).toContain('<meta name="robots" content="index,follow" />');
    expect(html).toContain('<meta property="og:url" content="https://app.parkio.dev/explore" />');
    expect(html).toContain('Live public parking explore');
    expect(html).toContain('Read-only municipal parking discovery');
    expect(html).toContain('source-labelled availability');
    expect(html).toContain('Controlled public beta');
    expect(html).toContain('No account required');
    expect(html).not.toMatch(/fixture|mock|snapshot/i);

    const jsonLd = html.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/)?.[1];
    const parsed = JSON.parse(jsonLd ?? '{}') as { '@type': string; url: string };
    expect(parsed).toMatchObject({
      '@type': 'WebApplication',
      url: 'https://app.parkio.dev/explore',
    });
  });

  it('publishes an exact single-URL sitemap and disallows private routes', () => {
    const robots = readFileSync(resolve(publicDir, 'robots.txt'), 'utf8');
    const sitemap = readFileSync(resolve(publicDir, 'sitemap.xml'), 'utf8');

    expect(robots).toContain('Allow: /explore');
    for (const path of [
      '/login', '/register', '/forgot-password', '/reset-password', '/check-email',
      '/verify-email', '/map', '/spots/', '/facilities/', '/my-spots', '/upload',
      '/profile', '/reports', '/notifications', '/gamification', '/leaderboard',
      '/moderation', '/admin',
    ]) {
      expect(robots).toContain(`Disallow: ${path}`);
    }
    expect(robots).toContain('Sitemap: https://app.parkio.dev/sitemap.xml');
    expect(sitemap.match(/<loc>/g)).toHaveLength(1);
    expect(sitemap).toContain('<loc>https://app.parkio.dev/explore</loc>');
    expect(sitemap).not.toMatch(/login|register|\/map|profile|admin/);
  });
});
