#!/usr/bin/env node
/**
 * Release-artifact integrity gate. Run against a built APK/AAB *before*
 * distributing it:
 *
 *   node scripts/verify-release-artifact.mjs <artifact.(apk|aab)> <profile>
 *
 * profiles: production | hosted-beta | development-validation
 *
 * Verifies, without any external tooling (pure Node, own zip reader):
 *   1. The JS bundle bakes the API base URL of the *intended* environment and
 *      not another environment's URL.
 *   2. The Android manifest does not enable cleartext traffic or `debuggable`
 *      (except the development-validation profile, which is cleartext by design
 *      and must NEVER be shipped).
 *   3. `SYSTEM_ALERT_WINDOW` does not ship in distributable artifacts
 *      (blocked via app.json `blockedPermissions`; debug builds re-add it).
 *
 * Exit code 0 = pass, 1 = failed check, 2 = usage/IO error.
 *
 * Detection notes: manifest checks look for the attribute/permission *name*
 * in the binary manifest (AXML string pool for APKs is UTF-8 or UTF-16LE;
 * AAB manifests are protobuf with plain UTF-8 strings). Attribute names only
 * enter those tables when the attribute is actually set, so presence == set.
 * If you legitimately set `usesCleartextTraffic="false"` explicitly, this
 * gate will flag it — inspect with `aapt dump xmltree` and adjust.
 */

import { Buffer } from 'node:buffer';
import { readFileSync } from 'node:fs';
import { inflateRawSync } from 'node:zlib';

const PROFILES = {
  production: {
    expectUrl: 'https://api.parkio.dev/api/v1',
    forbidUrls: [],
    allowCleartext: false,
  },
  'hosted-beta': {
    expectUrl: 'https://api.parkio.dev/api/v1',
    forbidUrls: ['https://beta-api.parkio.dev/api/v1'],
    allowCleartext: false,
  },
  'development-validation': {
    expectUrl: 'http://10.0.2.2:8080/api/v1',
    forbidUrls: [],
    allowCleartext: true,
  },
};

// ---------------------------------------------------------------- zip reader

/** Minimal read-only ZIP: central directory scan + stored/deflate extraction. */
function readZipEntry(buf, entryName) {
  // End Of Central Directory: scan backwards for PK\x05\x06 in the last 64 KiB + 22.
  const maxScan = Math.min(buf.length, 65_557);
  let eocd = -1;
  for (let i = buf.length - 22; i >= buf.length - maxScan; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error('not a zip archive (no end-of-central-directory)');
  const count = buf.readUInt16LE(eocd + 10);
  let offset = buf.readUInt32LE(eocd + 16);

  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(offset) !== 0x02014b50) throw new Error('corrupt central directory');
    const method = buf.readUInt16LE(offset + 10);
    const compressedSize = buf.readUInt32LE(offset + 20);
    const nameLength = buf.readUInt16LE(offset + 28);
    const extraLength = buf.readUInt16LE(offset + 30);
    const commentLength = buf.readUInt16LE(offset + 32);
    const localHeaderOffset = buf.readUInt32LE(offset + 42);
    const name = buf.toString('utf8', offset + 46, offset + 46 + nameLength);

    if (name === entryName) {
      // Local header repeats name/extra lengths; data begins after them.
      const lhNameLength = buf.readUInt16LE(localHeaderOffset + 26);
      const lhExtraLength = buf.readUInt16LE(localHeaderOffset + 28);
      const dataStart = localHeaderOffset + 30 + lhNameLength + lhExtraLength;
      const data = buf.subarray(dataStart, dataStart + compressedSize);
      if (method === 0) return Buffer.from(data);
      if (method === 8) return inflateRawSync(data);
      throw new Error(`unsupported compression method ${method} for ${entryName}`);
    }
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return null;
}

// ------------------------------------------------------------------- helpers

/** True when `text` occurs in `buf` as UTF-8 or UTF-16LE bytes. */
function containsString(buf, text) {
  if (buf.includes(Buffer.from(text, 'utf8'))) return true;
  return buf.includes(Buffer.from(text, 'utf16le'));
}

const results = [];
function check(label, ok, detail = '') {
  results.push({ label, ok, detail });
  console.log(`${ok ? '  PASS' : '  FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
}

// ---------------------------------------------------------------------- main

const [, , artifactPath, profileName] = process.argv;
const profile = PROFILES[profileName];
if (!artifactPath || !profile) {
  console.error(
    'Usage: node scripts/verify-release-artifact.mjs <artifact.(apk|aab)> <production|hosted-beta|development-validation>',
  );
  process.exit(2);
}

let archive;
try {
  archive = readFileSync(artifactPath);
} catch (error) {
  console.error(`Cannot read ${artifactPath}: ${error.message}`);
  process.exit(2);
}

const isAab = artifactPath.toLowerCase().endsWith('.aab');
const bundlePath = isAab ? 'base/assets/index.android.bundle' : 'assets/index.android.bundle';
const manifestPath = isAab ? 'base/manifest/AndroidManifest.xml' : 'AndroidManifest.xml';

console.log(`Verifying ${artifactPath} against profile "${profileName}"\n`);

const jsBundle = readZipEntry(archive, bundlePath);
check('JS bundle embedded', jsBundle !== null, bundlePath);
if (jsBundle) {
  check(
    `bakes ${profileName} API URL`,
    containsString(jsBundle, profile.expectUrl),
    profile.expectUrl,
  );
  for (const forbidden of profile.forbidUrls) {
    check(`does not bake ${forbidden}`, !containsString(jsBundle, forbidden));
  }
}

const manifest = readZipEntry(archive, manifestPath);
check('Android manifest present', manifest !== null, manifestPath);
if (manifest) {
  const cleartext = containsString(manifest, 'usesCleartextTraffic');
  if (profile.allowCleartext) {
    console.log(
      `  INFO  cleartext traffic ${cleartext ? 'ENABLED' : 'not set'} — validation build, NEVER ship this artifact`,
    );
  } else {
    check('no cleartext traffic attribute', !cleartext);
  }
  check('not debuggable', !containsString(manifest, 'debuggable'));
  if (!profile.allowCleartext) {
    check('SYSTEM_ALERT_WINDOW stripped', !containsString(manifest, 'SYSTEM_ALERT_WINDOW'));
  }
}

const failed = results.filter((r) => !r.ok);
console.log(
  `\n${failed.length === 0 ? 'OK' : 'FAILED'}: ${results.length - failed.length}/${results.length} checks passed`,
);
process.exit(failed.length === 0 ? 0 : 1);
