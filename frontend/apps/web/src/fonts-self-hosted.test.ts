import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

describe('self-hosted fonts', () => {
  it('index.html does not load Google Fonts CDN', () => {
    const html = readFileSync(resolve(__dirname, '../index.html'), 'utf8');
    expect(html).not.toMatch(/fonts\.googleapis\.com/);
    expect(html).not.toMatch(/fonts\.gstatic\.com/);
  });

  it('global styles import bundled font packages', () => {
    const css = readFileSync(resolve(__dirname, './styles/index.css'), 'utf8');
    expect(css).toContain('@fontsource-variable/inter');
    expect(css).toContain('@fontsource/material-symbols-outlined');
  });
});