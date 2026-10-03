import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import type { Plugin } from 'vite';

/** Placeholder in public/sw.js that the build replaces with the release version. */
export const SW_VERSION_PLACEHOLDER = '__PARKIO_SW_VERSION__';

/** A file of a built release: its path relative to the output directory and its content. */
export type ReleaseFile = readonly [name: string, content: string | Uint8Array];

/**
 * A short digest of a release: every file's name and content. A release that changes only a
 * public/ file (offline page, manifest, icon) therefore also gets a new worker and cache, and
 * identical builds keep the same version.
 */
export function serviceWorkerVersion(files: Iterable<ReleaseFile>): string {
  const digest = createHash('sha256');
  const sorted = [...files].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
  for (const [name, content] of sorted) {
    digest.update(`${name}\0${createHash('sha256').update(content).digest('hex')}\n`);
  }
  return digest.digest('hex').slice(0, 16);
}

/** The files under a build output directory, without the worker itself. */
export function releaseFiles(outDir: string): ReleaseFile[] {
  const files: ReleaseFile[] = [];
  const walk = (directory: string) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        walk(file);
      } else {
        files.push([path.relative(outDir, file).split(path.sep).join('/'), readFileSync(file)]);
      }
    }
  };
  walk(outDir);
  return files.filter(([name]) => name !== 'sw.js');
}

export function stampServiceWorker(source: string, version: string): string {
  if (!source.includes(SW_VERSION_PLACEHOLDER)) {
    throw new Error(`sw.js has no ${SW_VERSION_PLACEHOLDER} placeholder`);
  }
  if (!/^[0-9a-f]{16}$/.test(version)) {
    throw new Error(`unexpected service worker version: ${version}`);
  }
  return source.replaceAll(SW_VERSION_PLACEHOLDER, version);
}

/**
 * Stamps dist/sw.js once the build output is written (CL-F39.3): each release then installs a
 * new worker whose activate step deletes the previous release's cache, so hashed assets do not
 * accumulate. The public/ files are already in the output directory at this point.
 */
export function serviceWorkerVersionPlugin(): Plugin {
  let outDir = '';
  return {
    name: 'parkio-service-worker-version',
    apply: 'build',
    configResolved(config) {
      outDir = path.resolve(config.root, config.build.outDir);
    },
    writeBundle() {
      const worker = path.join(outDir, 'sw.js');
      if (!existsSync(worker)) {
        throw new Error(`service worker not found at ${worker}`);
      }
      const version = serviceWorkerVersion(releaseFiles(outDir));
      writeFileSync(worker, stampServiceWorker(readFileSync(worker, 'utf8'), version));
    },
  };
}
