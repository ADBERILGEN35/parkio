import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import test from 'node:test';

const here = path.dirname(fileURLToPath(import.meta.url));
const sanitizer = path.join(here, 'sanitize-compose-config.mjs');

const sentinels = [
  'SECRET_SENTINEL_DB_PASSWORD',
  'SECRET_SENTINEL_SLACK_URL',
  'SECRET_SENTINEL_RESEND_KEY',
];

test('emits structural Compose evidence without resolved values', () => {
  const input = {
    name: 'parkio',
    services: {
      api: {
        image: 'parkio/api:sha-test',
        environment: {
          POSTGRES_PASSWORD: sentinels[0],
          SLACK_WEBHOOK_URL: sentinels[1],
          RESEND_API_KEY: sentinels[2],
        },
        build: { args: { VITE_MAPTILER_KEY: 'public-but-redacted' } },
        ports: [{ host_ip: '127.0.0.1', published: '8080', target: 8080 }],
        healthcheck: {
          test: ['CMD-SHELL', `probe --password ${sentinels[0]}`],
          interval: '10s',
          retries: 3,
        },
      },
    },
  };

  const result = spawnSync(process.execPath, [sanitizer], {
    input: JSON.stringify(input),
    encoding: 'utf8',
  });
  assert.equal(result.status, 0, result.stderr);
  for (const sentinel of sentinels) assert.equal(result.stdout.includes(sentinel), false);
  assert.equal(result.stdout.includes('public-but-redacted'), false);

  const output = JSON.parse(result.stdout);
  assert.deepEqual(output.services[0].environmentNames, [
    'POSTGRES_PASSWORD',
    'RESEND_API_KEY',
    'SLACK_WEBHOOK_URL',
  ]);
  assert.deepEqual(output.services[0].buildArgumentNames, ['VITE_MAPTILER_KEY']);
  assert.equal(output.services[0].healthcheck.configured, true);
  assert.equal('test' in output.services[0].healthcheck, false);
});

test('fails closed on invalid input', () => {
  const result = spawnSync(process.execPath, [sanitizer], {
    input: 'not-json',
    encoding: 'utf8',
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /invalid Compose JSON/);
});
