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
    // Rendered at container start from the deployment's origins (B9); no static https: wildcard.
    const csp = /add_header Content-Security-Policy "([^"]+)" always;/.exec(nginxConfig)?.[1] ?? '';
    expect(csp).toContain('connect-src ${PARKIO_WEB_CSP_CONNECT_SRC};');
    expect(csp).not.toMatch(/connect-src[^;]*https:(\s|;)/);
    // img-src is derived from connect-src at start (CL-F39.2); no static https: wildcard either.
    expect(csp).toContain('img-src ${PARKIO_WEB_CSP_IMG_SRC};');
    expect(csp).not.toMatch(/img-src[^;]*https:(\s|;)/);
    expect(nginxConfig).toContain('add_header Referrer-Policy "strict-origin-when-cross-origin" always;');
    expect(nginxConfig).toContain('add_header X-Content-Type-Options "nosniff" always;');
    expect(nginxConfig).toContain('add_header X-Frame-Options "DENY" always;');
    expect(nginxConfig).toContain('add_header Permissions-Policy');
  });
});
