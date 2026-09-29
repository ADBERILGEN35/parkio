/**
 * Routing / auth boundary documentation tests for Wave 1.
 * Source-level assertions that public and main guards stay separate.
 */
import fs from 'node:fs';
import path from 'node:path';

const appRoot = path.join(__dirname, '../../../../app');

function readApp(relative: string): string {
  return fs.readFileSync(path.join(appRoot, relative), 'utf8');
}

describe('public Explore routing boundaries', () => {
  it('index routes anonymous post-onboarding to public Explore', () => {
    const source = readApp('index.tsx');
    expect(source).toContain("href=\"/(public)/explore\"");
    expect(source).toContain("href=\"/(main)/(tabs)/map\"");
  });

  it('main layout still redirects anonymous away from private shell', () => {
    const source = readApp('(main)/_layout.tsx');
    expect(source).toContain("authStatus === 'anonymous'");
    expect(source).toContain("href=\"/(onboarding)/welcome\"");
  });

  it('public layout redirects authenticated users to main map', () => {
    const source = readApp('(public)/_layout.tsx');
    expect(source).toContain("authStatus === 'authenticated'");
    expect(source).toContain("href=\"/(main)/(tabs)/map\"");
  });

  it('welcome exposes guest Explore CTA without removing sign-in', () => {
    const source = readApp('(onboarding)/welcome.tsx');
    expect(source).toContain("onboarding.welcome.explore");
    expect(source).toContain('/(public)/explore');
    expect(source).toContain('/(auth)/login');
  });

  it('root stack registers the public group', () => {
    const source = readApp('_layout.tsx');
    expect(source).toContain('name="(public)"');
  });
});
