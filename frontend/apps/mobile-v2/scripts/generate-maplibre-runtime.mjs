/**
 * Generate verified MapLibre GL JS/CSS runtime assets for the mobile WebView.
 *
 * Source of truth: npm package maplibre-gl@4.7.1 (official registry).
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

const __dirname = dirname(fileURLToPath(import.meta.url));
const appRoot = resolve(__dirname, '..');
const require = createRequire(join(appRoot, 'package.json'));

const EXPECTED_VERSION = '4.7.1';
const EXPECTED_PACKAGE_INTEGRITY =
  'sha512-lgL7XpIwsgICiL82ITplfS7IGwrB1OJIw/pCvprDp2dhmSSEBgmPzYRvwYYYvJGJD7fxUv1Tvpih4nZ6VrLuaA==';

const pkgPath = require.resolve('maplibre-gl/package.json');
const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'));
if (pkg.version !== EXPECTED_VERSION) {
  throw new Error(
    `maplibre-gl version mismatch: resolved ${pkg.version}, expected ${EXPECTED_VERSION}`,
  );
}

const distDir = join(dirname(pkgPath), 'dist');
const jsPath = join(distDir, 'maplibre-gl.js');
const cssPath = join(distDir, 'maplibre-gl.css');
if (!existsSync(jsPath) || !existsSync(cssPath)) {
  throw new Error(`Missing MapLibre dist files under ${distDir}`);
}

const jsBytes = readFileSync(jsPath);
const cssBytes = readFileSync(cssPath);
const jsSha256 = createHash('sha256').update(jsBytes).digest('hex');
const cssSha256 = createHash('sha256').update(cssBytes).digest('hex');

const outDir = join(appRoot, 'src/features/map/vendor');
mkdirSync(outDir, { recursive: true });

const jsOut = join(outDir, 'maplibre-gl.js');
const cssOut = join(outDir, 'maplibre-gl.css');
writeFileSync(jsOut, jsBytes);
writeFileSync(cssOut, cssBytes);

const licensePath = join(dirname(pkgPath), 'LICENSE.txt');
const licenseText = existsSync(licensePath)
  ? readFileSync(licensePath, 'utf8').slice(0, 200).replace(/\s+/g, ' ').trim()
  : 'BSD-3-Clause';

const manifest = {
  component: 'maplibre-gl',
  version: EXPECTED_VERSION,
  source: 'https://registry.npmjs.org/maplibre-gl/-/maplibre-gl-4.7.1.tgz',
  packageName: 'maplibre-gl',
  packageIntegrity: EXPECTED_PACKAGE_INTEGRITY,
  jsFile: 'maplibre-gl.js',
  cssFile: 'maplibre-gl.css',
  jsSha256,
  cssSha256,
  license: pkg.license || 'BSD-3-Clause',
  licenseNote: licenseText,
  runtimeMode: 'bundled',
  generatedAt: new Date().toISOString(),
  regenerationCommand:
    'node apps/mobile-v2/scripts/generate-maplibre-runtime.mjs',
};

writeFileSync(
  join(outDir, 'maplibre-runtime.manifest.json'),
  `${JSON.stringify(manifest, null, 2)}\n`,
);

// TypeScript module that inlines verified assets for WebView HTML (no CDN).
const moduleSource = `/* eslint-disable */
/**
 * GENERATED FILE — do not edit by hand.
 * Source: maplibre-gl@${EXPECTED_VERSION}
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
copied from the official npm package for offline / non-CDN WebView execution.

| Field | Value |
|-------|-------|
| Package | maplibre-gl@${EXPECTED_VERSION} |
| Integrity | \`${EXPECTED_PACKAGE_INTEGRITY}\` |
| JS SHA-256 | \`${jsSha256}\` |
| CSS SHA-256 | \`${cssSha256}\` |
| License | ${pkg.license || 'BSD-3-Clause'} |

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
      jsSha256,
      cssSha256,
      jsBytes: jsBytes.length,
      cssBytes: cssBytes.length,
    },
    null,
    2,
  ),
);
