import { readFileSync } from 'node:fs';
import { join } from 'node:path';

describe('WP-07 single SDK ownership', () => {
  it('keeps createApiClient in the approved owner module only once', () => {
    const source = readFileSync(join(__dirname, '../api.ts'), 'utf8');
    const matches = source.match(/createApiClient\s*\(/g) ?? [];
    expect(matches.length).toBe(1);
    expect(source).toContain("defaultHeaders: { 'X-Parkio-Client': 'mobile' }");
    expect(source).toContain('setRefreshHandler');
  });
});