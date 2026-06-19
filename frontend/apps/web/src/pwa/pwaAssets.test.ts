import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const publicDir = resolve(dirname(fileURLToPath(import.meta.url)), '../../public');

describe('PWA assets', () => {
  it('ships an installable web app manifest', () => {
    const manifest = JSON.parse(
      readFileSync(resolve(publicDir, 'manifest.webmanifest'), 'utf8'),
    ) as {
      name: string;
      display: string;
      start_url: string;
      icons: Array<{ purpose?: string }>;
    };

    expect(manifest.name).toBe('Parkio');
    expect(manifest.display).toBe('standalone');
    expect(manifest.start_url).toBe('/');
    expect(manifest.icons.some((icon) => icon.purpose === 'maskable')).toBe(true);
  });

  it('keeps API and auth traffic out of the service worker cache', () => {
    const worker = readFileSync(resolve(publicDir, 'sw.js'), 'utf8');

    expect(worker).toContain('SENSITIVE_PATH');
    expect(worker).toContain("request.headers.has('authorization')");
    expect(worker).not.toContain("url.pathname.startsWith('/api/')");
    expect(worker).not.toContain("url.pathname.startsWith('/auth/')");
    expect(worker).not.toContain('VITE_API_BASE_URL');
  });
});
