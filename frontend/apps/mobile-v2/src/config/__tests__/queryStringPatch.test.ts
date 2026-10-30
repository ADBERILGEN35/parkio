import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

/**
 * expo-router parses URLs with query-string 7, which pnpm patches with a CommonJS copy of
 * decode-uri-component 0.5.1 (frontend/patches/query-string@7.1.3.patch; CVE-2026-118664, #517).
 * The app's other tests mock expo-router, so this one loads the installed query-string the way
 * expo-router resolves it.
 */
type QueryString = { parse(query: string): Record<string, unknown> };

const fromApp = createRequire(path.join(__dirname, '../../../package.json'));
const fromExpoRouter = createRequire(fromApp.resolve('expo-router'));
const entry = fromExpoRouter.resolve('query-string');
const queryString = fromExpoRouter('query-string') as QueryString;

describe('query-string as expo-router loads it', () => {
  it('is the patched 7.1.3 with the vendored decoder', () => {
    const packageDir = path.dirname(entry);
    expect(JSON.parse(fs.readFileSync(path.join(packageDir, 'package.json'), 'utf8')).version).toBe('7.1.3');
    expect(fs.readFileSync(entry, 'utf8')).toContain("require('./decode-uri-component.js')");
    expect(fs.existsSync(path.join(packageDir, 'decode-uri-component.js'))).toBe(true);
    // The workspace override removes the vulnerable package, so it cannot be loaded by name either.
    expect(() => createRequire(entry).resolve('decode-uri-component')).toThrow();
  });

  it('decodes valid and malformed input like decode-uri-component 0.5.1', () => {
    expect({ ...queryString.parse('n=st%C3%A5le&bad=%E0%A4%A&r=%2Fhome%3Fx%3D1') }).toEqual({
      n: 'ståle',
      bad: '%E0%A4%A',
      r: '/home?x=1',
    });
  });

  it('decodes a long run of malformed bytes in linear time', () => {
    // 0.2.2 needs about ten seconds for 1000 runs and grows quadratically; 0.5.1 needs well under a millisecond.
    const started = Date.now();
    const parsed = queryString.parse(`a=${'%C2'.repeat(4000)}%`);
    expect(Date.now() - started).toBeLessThan(1000);
    expect(typeof parsed.a).toBe('string');
  });
});
