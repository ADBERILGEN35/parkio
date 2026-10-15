import assert from 'node:assert/strict';
import { test } from 'node:test';

import { isExampleEnvPlaceholder } from './map-key-placeholder.mjs';

const FORBIDDEN = [
  'CHANGE_ME',
  'dummy',
  'placeholder',
  '  change_me  ',
  'DUMMY',
  'Placeholder',
  '  DuMmY ',
  '	PLACEHOLDER	',
];

const ALLOWED_SYNTHETIC = 'fixture-public-map-key-never-use-in-production';
const ALLOWED_REALISTIC = 'Zq3x7Vb2Nw8Kp4Rt6Ym1';

test('rejects CHANGE_ME, dummy, placeholder with whitespace/case variants (F-05R)', () => {
  for (const value of FORBIDDEN) {
    assert.equal(
      isExampleEnvPlaceholder(value),
      true,
      `expected placeholder rejection for ${JSON.stringify(value)}`,
    );
  }
});

test('still allows deliberately scoped synthetic CI fixtures and realistic keys (F-05R)', () => {
  assert.equal(isExampleEnvPlaceholder(ALLOWED_SYNTHETIC), false);
  assert.equal(isExampleEnvPlaceholder(ALLOWED_REALISTIC), false);
});
