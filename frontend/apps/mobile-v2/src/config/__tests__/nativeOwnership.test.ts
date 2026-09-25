import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const ownershipScript = path.join(__dirname, '../../../scripts/assert-native-ownership.mjs');
const doctorScript = path.join(__dirname, '../../../scripts/run-expo-doctor.mjs');

function runOwnership(env: NodeJS.ProcessEnv = {}) {
  return spawnSync(process.execPath, [ownershipScript], {
    encoding: 'utf8',
    env: { ...process.env, ...env },
  });
}

function runDoctorEval(status: string, output: string, spawnError?: string) {
  return spawnSync(process.execPath, [doctorScript], {
    encoding: 'utf8',
    env: {
      ...process.env,
      PARKIO_DOCTOR_EVAL_STATUS: status,
      PARKIO_DOCTOR_EVAL_OUTPUT: output,
      ...(spawnError ? { PARKIO_DOCTOR_EVAL_SPAWN_ERROR: spawnError } : {}),
    },
  });
}

const cngOutput = [
  'Running 21 checks on your project...',
  '20/21 checks passed. 1 checks failed. Possible issues detected:',
  '',
  '\u2716 Check for app config fields that may not be synced in a non-CNG project',
  'This project contains native project folders but also has native configuration properties in app.json, indicating it is configured to use Prebuild. When the android/ios folders are present, EAS Build will not sync the following properties: orientation, icon, scheme, userInterfaceStyle, ios, android, plugins.',
  '',
  '1 check failed, indicating possible issues with the project.',
].join('\n').replaceAll('\\u2716', '\u2716');

describe('mobile-v2 native ownership', () => {
  it('passes against the committed android tree', () => {
    const result = runOwnership();
    expect(result.status).toBe(0);
    expect(result.stdout).toContain('[assert-native-ownership] PASS');
    expect(result.stdout).toContain('versionName=0.1.2-backnav');
  });

  it('fails when allowBackup is flipped on a copied tree', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'parkio-native-'));
    const appRoot = path.join(__dirname, '../../..');
    const files = [
      'app.json',
      'eas.json',
      '.gitignore',
      'android/app/build.gradle',
      'android/app/src/main/AndroidManifest.xml',
      'android/gradlew',
      'android/gradlew.bat',
      'scripts/run-android-release.mjs',
      'plugins/withAndroidAllowBackupFalse.js',
      'plugins/withAndroidCompatibility.js',
    ];
    for (const rel of files) {
      const dest = path.join(tmp, rel);
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      fs.copyFileSync(path.join(appRoot, rel), dest);
    }
    const manifestPath = path.join(tmp, 'android/app/src/main/AndroidManifest.xml');
    const mutated = fs
      .readFileSync(manifestPath, 'utf8')
      .replace('android:allowBackup="false"', 'android:allowBackup="true"');
    fs.writeFileSync(manifestPath, mutated);
    const result = runOwnership({ PARKIO_NATIVE_APP_ROOT: tmp });
    expect(result.status).not.toBe(0);
    expect(result.stderr).toContain('allowBackup');
  });

  it('does not suppress Mobile-v2 doctor in CI', () => {
    const yml = fs.readFileSync(
      path.join(__dirname, '../../../../../../.github/workflows/mobile-ci.yml'),
      'utf8',
    );
    const v2 = yml.split('mobile-legacy-checks')[0];
    expect(v2).not.toMatch(/continue-on-error:\s*true/);
    expect(v2).toContain('validate:native-ownership');
    expect(fs.existsSync(path.join(__dirname, '../../../NATIVE-BUILD.md'))).toBe(true);
  });
});

describe('run-expo-doctor fail-closed replacement', () => {
  it('accepts only the identified CNG diagnostic at 20/21', () => {
    const result = runDoctorEval('1', cngOutput);
    expect(result.status).toBe(0);
    expect(result.stdout).toContain('20/21 checks passed');
    expect(result.stdout).toContain('CNG-only diagnostic replaced by native ownership');
  });

  it('fails closed on an extra doctor failure', () => {
    const extra =
      cngOutput.replace(
        '20/21 checks passed. 1 checks failed',
        '19/21 checks passed. 2 checks failed',
      ) + '\n\u2716 Check that packages match versions required by installed Expo SDK\n';
    const result = runDoctorEval('1', extra.replaceAll('\\u2716', '\u2716'));
    expect(result.status).toBe(1);
    expect(result.stderr + result.stdout).toContain('blocking expo-doctor failures');
  });

  it('fails closed when the summary cannot be parsed', () => {
    const result = runDoctorEval(
      '1',
      '\u2716 Check for app config fields that may not be synced in a non-CNG project\n'.replaceAll(
        '\\u2716',
        '\u2716',
      ),
    );
    expect(result.status).toBe(1);
    expect(result.stderr + result.stdout).toContain('summary was not parsed');
  });

  it('fails closed on spawn errors and unexpected exits', () => {
    expect(runDoctorEval('1', cngOutput, 'ENOENT').status).toBe(1);
    expect(runDoctorEval('2', cngOutput).status).toBe(1);
    expect(
      runDoctorEval('1', cngOutput.replace('native project folders', 'something else')).status,
    ).toBe(1);
  });
});
