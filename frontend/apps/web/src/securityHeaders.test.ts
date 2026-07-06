import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const appDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');

describe('web production security headers', () => {
  it('ships browser security headers from the nginx host config', () => {
    const nginxConfig = readFileSync(resolve(appDir, 'nginx.conf'), 'utf8');

    expect(nginxConfig).toContain('add_header Content-Security-Policy');
    expect(nginxConfig).toContain("default-src 'self'");
    expect(nginxConfig).toContain("frame-ancestors 'none'");
    expect(nginxConfig).toContain("object-src 'none'");
    expect(nginxConfig).toContain("connect-src 'self' https:");
    expect(nginxConfig).toContain("img-src 'self' data: blob: https:");
    expect(nginxConfig).toContain('add_header Referrer-Policy "strict-origin-when-cross-origin" always;');
    expect(nginxConfig).toContain('add_header X-Content-Type-Options "nosniff" always;');
    expect(nginxConfig).toContain('add_header X-Frame-Options "DENY" always;');
    expect(nginxConfig).toContain('add_header Permissions-Policy');
  });
});
