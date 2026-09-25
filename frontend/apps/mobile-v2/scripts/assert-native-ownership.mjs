#!/usr/bin/env node
/**
 * Replacement for expo-doctor CNG sync when android/ is the release source
 * of truth. See NATIVE-BUILD.md.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const failures = [];

function fail(message) {
  failures.push(message);
}

function read(rel) {
  const filePath = path.join(appRoot, rel);
  if (!fs.existsSync(filePath)) {
    fail(`missing ${rel}`);
    return null;
  }
  return fs.readFileSync(filePath, 'utf8');
}

const appJsonRaw = read('app.json');
const appJson = appJsonRaw ? JSON.parse(appJsonRaw) : {};
const expo = appJson.expo ?? {};
const pkg = expo.android?.package;
const scheme = expo.scheme;
const orientation = expo.orientation;

if (pkg !== 'dev.parkio.mobilev2') {
  fail(`app.json android.package must be dev.parkio.mobilev2; got ${pkg}`);
}
if (scheme !== 'parkio-v2') {
  fail(`app.json scheme must be parkio-v2; got ${scheme}`);
}
if (orientation !== 'portrait') {
  fail(`app.json orientation must be portrait; got ${orientation}`);
}

const blocked = expo.android?.blockedPermissions ?? [];
for (const perm of [
  'android.permission.RECORD_AUDIO',
  'android.permission.SYSTEM_ALERT_WINDOW',
]) {
  if (!blocked.includes(perm)) {
    fail(`app.json missing blockedPermission ${perm}`);
  }
}

for (const plugin of expo.plugins ?? []) {
  const name = Array.isArray(plugin) ? plugin[0] : plugin;
  if (typeof name === 'string' && name.startsWith('./')) {
    if (!fs.existsSync(path.join(appRoot, name))) {
      fail(`plugin file missing: ${name}`);
    }
  }
}

for (const rel of [
  'android/gradlew',
  'android/gradlew.bat',
  'android/app/build.gradle',
  'android/app/src/main/AndroidManifest.xml',
]) {
  if (!fs.existsSync(path.join(appRoot, rel))) {
    fail(`missing ${rel}`);
  }
}

const gradle = read('android/app/build.gradle') ?? '';
const idMatch = gradle.match(/applicationId\s+'([^']+)'/);
const nsMatch = gradle.match(/namespace\s+'([^']+)'/);
if (!idMatch || idMatch[1] !== pkg) {
  fail(`gradle applicationId ${idMatch?.[1] ?? '(missing)'} != package ${pkg}`);
}
if (!nsMatch || nsMatch[1] !== pkg) {
  fail(`gradle namespace ${nsMatch?.[1] ?? '(missing)'} != package ${pkg}`);
}

const versionName = gradle.match(/versionName\s+"([^"]+)"/)?.[1];
const versionCode = gradle.match(/versionCode\s+(\d+)/)?.[1];
if (!versionName) {
  fail('gradle versionName missing');
}
if (!versionCode) {
  fail('gradle versionCode missing');
}

const manifest = read('android/app/src/main/AndroidManifest.xml') ?? '';
if (!manifest.includes('android:allowBackup="false"')) {
  fail('manifest allowBackup must be false');
}
if (!manifest.includes('android:scheme="parkio-v2"')) {
  fail('manifest missing parkio-v2 scheme');
}
if (!manifest.includes('android:screenOrientation="portrait"')) {
  fail('manifest must be portrait');
}
if (!manifest.includes('android.permission.RECORD_AUDIO') || !manifest.includes('tools:node="remove"')) {
  fail('RECORD_AUDIO must be present with tools:node=remove');
}
if (!manifest.includes('android.permission.SYSTEM_ALERT_WINDOW')) {
  fail('SYSTEM_ALERT_WINDOW must be present with tools:node=remove');
}

const recordAudioLine = manifest.split(/\r?\n/).find((line) => line.includes('RECORD_AUDIO'));
const alertLine = manifest.split(/\r?\n/).find((line) => line.includes('SYSTEM_ALERT_WINDOW'));
if (!recordAudioLine || !recordAudioLine.includes('tools:node="remove"')) {
  fail('RECORD_AUDIO must be tools:node=remove');
}
if (!alertLine || !alertLine.includes('tools:node="remove"')) {
  fail('SYSTEM_ALERT_WINDOW must be tools:node=remove');
}

const releaseScript = read('scripts/run-android-release.mjs') ?? '';
if (!releaseScript.includes("path.join(appRoot, 'android')")) {
  fail('run-android-release.mjs must target android/');
}
if (!releaseScript.includes('gradlew')) {
  fail('run-android-release.mjs must invoke gradlew');
}

const easRaw = read('eas.json');
const eas = easRaw ? JSON.parse(easRaw) : {};
const previewCmd = eas.build?.preview?.android?.gradleCommand;
const betaCmd = eas.build?.['hosted-beta']?.android?.gradleCommand;
const prodCmd = eas.build?.production?.android?.gradleCommand;
if (previewCmd !== ':app:assembleRelease') {
  fail(`eas preview gradleCommand must be :app:assembleRelease; got ${previewCmd}`);
}
if (betaCmd !== ':app:assembleRelease') {
  fail(`eas hosted-beta gradleCommand must be :app:assembleRelease; got ${betaCmd}`);
}
if (prodCmd !== ':app:bundleRelease') {
  fail(`eas production gradleCommand must be :app:bundleRelease; got ${prodCmd}`);
}

const gitignore = read('.gitignore') ?? '';
for (const rawLine of gitignore.split(/\r?\n/)) {
  const line = rawLine.trim();
  if (!line || line.startsWith('#')) {
    continue;
  }
  if (/(^|\/)android\/?$/.test(line.replace(/^\//, ''))) {
    fail('.gitignore ignores android/');
  }
}

if (failures.length) {
  console.error('[assert-native-ownership] FAILED');
  for (const item of failures) {
    console.error(`  - ${item}`);
  }
  process.exit(1);
}

console.log('[assert-native-ownership] PASS');
console.log(`  applicationId=${idMatch[1]} scheme=parkio-v2 allowBackup=false`);
console.log(
  `  store versionCode=${versionCode} versionName=${versionName} (android-owned; app.json version=${expo.version})`,
);
console.log('  EAS preview/hosted-beta=:app:assembleRelease production=:app:bundleRelease');
