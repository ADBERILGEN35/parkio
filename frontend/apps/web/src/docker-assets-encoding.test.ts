import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The hosted-beta web image is built from these files by Docker and nginx, which
 * only understand ASCII/UTF-8. A UTF-16 re-save (e.g. from a Windows editor) makes
 * `docker build` fail with "unknown instruction" and nginx unable to read its
 * config — regression guard for the encoding bug fixed in R6.3A.
 */
describe('docker build assets', () => {
  const files = ['../Dockerfile', '../nginx.conf', '../docker/15-parkio-web-csp.envsh'];

  it.each(files)('%s is UTF-8 without NUL bytes', (rel) => {
    const raw = readFileSync(resolve(__dirname, rel));
    expect(raw.includes(0), `${rel} contains NUL bytes (UTF-16 encoding?)`).toBe(false);
  });

  it('Dockerfile starts with a parseable instruction or comment', () => {
    const text = readFileSync(resolve(__dirname, '../Dockerfile'), 'utf8');
    expect(text.split('\n')[0]).toMatch(/^(#|FROM|ARG)/);
    expect(text).toMatch(/^FROM /m);
  });

  it('the CSP entrypoint script has LF line endings (sourced by /bin/sh in the image)', () => {
    const raw = readFileSync(resolve(__dirname, '../docker/15-parkio-web-csp.envsh'), 'utf8');
    expect(raw.includes('\r')).toBe(false);
    expect(raw.startsWith('#!/bin/sh\n')).toBe(true);
  });

  it('nginx.conf declares the SPA fallback', () => {
    const text = readFileSync(resolve(__dirname, '../nginx.conf'), 'utf8');
    expect(text).toContain('try_files $uri $uri/ /index.html;');
  });
});
