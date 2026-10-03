import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  type ReleaseFile,
  SW_VERSION_PLACEHOLDER,
  releaseFiles,
  serviceWorkerVersion,
  stampServiceWorker,
} from '../../vite-plugins/serviceWorkerVersion';

describe('service worker version stamping (CL-F39.3)', () => {
  const release: ReleaseFile[] = [
    ['index.html', '<script src="/assets/app-AAAA.js"></script>'],
    ['assets/app-AAAA.js', 'console.log("a")'],
    ['offline.html', 'offline'],
  ];

  it('derives a stable version from every file of the release', () => {
    const version = serviceWorkerVersion(release);

    expect(version).toMatch(/^[0-9a-f]{16}$/);
    expect(serviceWorkerVersion([...release].reverse())).toBe(version);
    expect(serviceWorkerVersion([...release.slice(0, 2), ['offline.html', 'offline, revised']])).not.toBe(version);
    expect(serviceWorkerVersion([release[0], ['assets/app-BBBB.js', 'console.log("a")'], release[2]])).not.toBe(
      version,
    );
  });

  it('reads the release without the worker itself', () => {
    const dir = mkdtempSync(join(tmpdir(), 'parkio-sw-version-'));
    try {
      mkdirSync(join(dir, 'assets'));
      writeFileSync(join(dir, 'assets', 'app-AAAA.js'), 'a');
      writeFileSync(join(dir, 'index.html'), 'i');
      writeFileSync(join(dir, 'sw.js'), 'worker');

      expect(releaseFiles(dir).map(([name]) => name).sort()).toEqual(['assets/app-AAAA.js', 'index.html']);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it('replaces the placeholder and refuses a worker without one', () => {
    const version = serviceWorkerVersion(release);

    expect(stampServiceWorker(`const v = '${SW_VERSION_PLACEHOLDER}';`, version)).toBe(`const v = '${version}';`);
    expect(() => stampServiceWorker("const v = 'fixed';", version)).toThrow(/placeholder/);
    expect(() => stampServiceWorker(`'${SW_VERSION_PLACEHOLDER}'`, 'not-a-version')).toThrow(/version/);
  });
});
