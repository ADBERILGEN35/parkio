import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

describe('mobile-v2 native ownership', () => {
  it('passes against the committed android tree', () => {
    const script = path.join(__dirname, '../../../scripts/assert-native-ownership.mjs');
    const result = spawnSync(process.execPath, [script], { encoding: 'utf8' });
    expect(result.status).toBe(0);
    expect(result.stdout).toContain('[assert-native-ownership] PASS');
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
