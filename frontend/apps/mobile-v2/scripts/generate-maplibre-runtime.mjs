/**
 * Generate verified MapLibre GL JS/CSS runtime assets for the mobile WebView.
 *
 * Source of truth: npm package maplibre-gl@6.4.1 (official registry).
 * MapLibre v6 ships ESM-only (maplibre-gl.mjs); this script bundles a single
 * IIFE that exposes `maplibregl` for classic inline <script> WebView HTML.
 *
 * Do not edit generated files by hand — re-run this script.
 *
 * Usage (from frontend/):
 *   node apps/mobile-v2/scripts/generate-maplibre-runtime.mjs
 */
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';

const __dirname = dirname(fileURLToPath(import.meta.url));
const appRoot = resolve(__dirname, '..');
const require = createRequire(join(appRoot, 'package.json'));

const EXPECTED_VERSION = '6.4.1';
const EXPECTED_PACKAGE_INTEGRITY =
  'sha512-KzxQKtfBu/pSz1C+yW1hNS9eyj2h2lC7ufdAi6/SEt177n3oAfDfmUmslRfJdXY7ReAFBcnvwsqmiyoDhtA9GQ==';

const pkgPath = require.resolve('maplibre-gl/package.json');
const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'));
if (pkg.version !== EXPECTED_VERSION) {
  throw new Error(
    `maplibre-gl version mismatch: resolved ${pkg.version}, expected ${EXPECTED_VERSION}`,
  );
}

const distDir = join(dirname(pkgPath), 'dist');
const mjsPath = join(distDir, 'maplibre-gl.mjs');
const cssPath = join(distDir, 'maplibre-gl.css');
if (!existsSync(mjsPath) || !existsSync(cssPath)) {
  throw new Error(`Missing MapLibre dist files under ${distDir}`);
}

const outDir = join(appRoot, 'src/features/map/vendor');
mkdirSync(outDir, { recursive: true });

const jsOut = join(outDir, 'maplibre-gl.js');
const cssOut = join(outDir, 'maplibre-gl.css');

// MapLibre 6.x no longer publishes UMD. Bundle ESM → IIFE for WebView.
let esbuildBin;
try {
  esbuildBin = require.resolve('esbuild/bin/esbuild');
} catch {
  throw new Error(
    'esbuild is required to bundle maplibre-gl@6 ESM for WebView. Install apps/mobile-v2 devDependency esbuild.',
  );
}

const bundle = spawnSync(
  process.execPath,
  [
    esbuildBin,
    mjsPath,
    '--bundle',
    '--format=iife',
    '--global-name=maplibregl',
    `--outfile=${jsOut}`,
    '--log-level=warning',
  ],
  { encoding: 'utf8' },
);
if (bundle.status !== 0) {
  throw new Error(
    `esbuild MapLibre IIFE failed (status ${bundle.status}):\n${bundle.stderr || bundle.stdout}`,
  );
}

const cssBytes = readFileSync(cssPath);
writeFileSync(cssOut, cssBytes);

const jsBytes = readFileSync(jsOut);
const jsSha256 = createHash('sha256').update(jsBytes).digest('hex');
const cssSha256 = createHash('sha256').update(cssBytes).digest('hex');

const licensePath = join(dirname(pkgPath), 'LICENSE.txt');
const licenseText = existsSync(licensePath)
  ? readFileSync(licensePath, 'utf8').slice(0, 200).replace(/\s+/g, ' ').trim()
  : 'BSD-3-Clause';

const manifest = {
  component: 'maplibre-gl',
  version: EXPECTED_VERSION,
  source: 'https://registry.npmjs.org/maplibre-gl/-/maplibre-gl-6.4.1.tgz',
  packageName: 'maplibre-gl',
  packageIntegrity: EXPECTED_PACKAGE_INTEGRITY,
  jsFile: 'maplibre-gl.js',
  cssFile: 'maplibre-gl.css',
  jsSha256,
  cssSha256,
  license: pkg.license || 'BSD-3-Clause',
  licenseNote: licenseText,
  runtimeMode: 'esbuild-iife-from-esm',
  esmEntry: 'dist/maplibre-gl.mjs',
  generatedAt: new Date().toISOString(),
  regenerationCommand:
    'node apps/mobile-v2/scripts/generate-maplibre-runtime.mjs',
};

writeFileSync(
  join(outDir, 'maplibre-runtime.manifest.json'),
  `${JSON.stringify(manifest, null, 2)}\n`,
);

const moduleSource = `/* eslint-disable */
/**
 * GENERATED FILE — do not edit by hand.
 * Source: maplibre-gl@${EXPECTED_VERSION} (ESM → esbuild IIFE for WebView)
 * packageIntegrity: ${EXPECTED_PACKAGE_INTEGRITY}
 * jsSha256: ${jsSha256}
 * cssSha256: ${cssSha256}
 * Regenerate: node apps/mobile-v2/scripts/generate-maplibre-runtime.mjs
 */
export const MAPLIBRE_RUNTIME_VERSION = ${JSON.stringify(EXPECTED_VERSION)} as const;
export const MAPLIBRE_RUNTIME_JS_SHA256 = ${JSON.stringify(jsSha256)} as const;
export const MAPLIBRE_RUNTIME_CSS_SHA256 = ${JSON.stringify(cssSha256)} as const;
export const MAPLIBRE_RUNTIME_PACKAGE_INTEGRITY = ${JSON.stringify(EXPECTED_PACKAGE_INTEGRITY)} as const;
export const MAPLIBRE_RUNTIME_CSS = ${JSON.stringify(cssBytes.toString('utf8'))};
export const MAPLIBRE_RUNTIME_JS = ${JSON.stringify(jsBytes.toString('utf8'))};
`;

writeFileSync(join(outDir, 'maplibreRuntime.generated.ts'), moduleSource);

const readme = `# Generated MapLibre runtime (mobile-v2 WebView)

This directory holds the **exact** MapLibre GL JS ${EXPECTED_VERSION} runtime
derived from the official npm package for offline / non-CDN WebView execution.

MapLibre v6 publishes ESM-only (\`maplibre-gl.mjs\`). The checked-in
\`maplibre-gl.js\` is an esbuild IIFE bundle (\`global-name=maplibregl\`) so the
existing classic inline \`<script>\` WebView document keeps working.

| Field | Value |
|-------|-------|
| Package | maplibre-gl@${EXPECTED_VERSION} |
| Integrity | \`${EXPECTED_PACKAGE_INTEGRITY}\` |
| JS SHA-256 | \`${jsSha256}\` |
| CSS SHA-256 | \`${cssSha256}\` |
| License | ${pkg.license || 'BSD-3-Clause'} |
| Runtime mode | esbuild IIFE from ESM |

Do not manually edit \`maplibre-gl.js\`, \`maplibre-gl.css\`, or
\`maplibreRuntime.generated.ts\`.

Regenerate:

\`\`\`bash
node apps/mobile-v2/scripts/generate-maplibre-runtime.mjs
\`\`\`
`;

writeFileSync(join(outDir, 'README.md'), readme);

console.log(
  JSON.stringify(
    {
      ok: true,
      version: EXPECTED_VERSION,
      runtimeMode: 'esbuild-iife-from-esm',
      jsSha256,
      cssSha256,
      jsBytes: jsBytes.length,
      cssBytes: cssBytes.length,
    },
    null,
    2,
  ),
);
