import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * CL-F39.4 (owner decision B9). The web image and the Caddy edge both define a
 * Content-Security-Policy, and three cases decide which one is enforced:
 * - Caddy's production SPA header block deletes Server, which defers it to response time, so
 *   Caddy's policy replaces the image's on the edge;
 * - without the edge, the image's policy applies;
 * - an edge that passes both makes browsers enforce both.
 *
 * The image renders connect-src at start from the same inputs as Caddy
 * (docker/15-parkio-web-csp.envsh). This test renders both policies from the repository files
 * for one deployment and checks three things: the image is equal to or stricter than Caddy in
 * every directive, connect-src is identical, and every case lets the SPA reach its origins and
 * nothing else. Runtime validation checks the live headers.
 */
const appDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoDir = resolve(appDir, '../../..');
const envsh = resolve(appDir, 'docker/15-parkio-web-csp.envsh');
const hasSh = !spawnSync('sh', ['-c', 'true']).error;

const DEPLOYMENT = {
  PARKIO_DOMAIN: 'api.parkio.test',
  PARKIO_MEDIA_DOMAIN: 'media.parkio.test',
  PARKIO_MAP_CONNECT_SRC: 'https://api.maptiler.com',
};
const APP_ORIGIN = 'https://app.parkio.test';

type Policy = Map<string, string[]>;

function runEnvsh(env: Record<string, string>) {
  return spawnSync('sh', ['-c', '. "$0"; printf "%s" "$PARKIO_WEB_CSP_CONNECT_SRC"', envsh], {
    env: { PATH: process.env.PATH ?? '/usr/bin:/bin', ...env },
    encoding: 'utf8',
  });
}

function imagePolicy(env: Record<string, string>): Policy {
  const template = readFileSync(resolve(appDir, 'nginx.conf'), 'utf8');
  const raw = /add_header Content-Security-Policy "([^"]+)" always;/.exec(template)?.[1];
  if (!raw) throw new Error('no Content-Security-Policy in nginx.conf');
  const rendered = runEnvsh(env);
  if (rendered.status !== 0) throw new Error(`envsh failed: ${rendered.stderr}`);
  return parse(raw.replaceAll('${PARKIO_WEB_CSP_CONNECT_SRC}', rendered.stdout));
}

function caddyPolicy(env: Record<string, string>): Policy {
  const caddyfile = readFileSync(resolve(repoDir, 'docker/caddy/Caddyfile'), 'utf8');
  const site = caddyfile.slice(caddyfile.indexOf('{$PARKIO_WEB_DOMAIN} {'));
  const raw = /Content-Security-Policy "([^"]+)"/.exec(site)?.[1];
  if (!raw) throw new Error('no SPA Content-Security-Policy in the Caddyfile');
  const substituted = raw.replace(/\{\$([A-Z0-9_]+)(?::([^}]*))?\}/g, (_match, name: string, fallback?: string) => {
    const value = env[name];
    return value !== undefined && value !== '' ? value : (fallback ?? '');
  });
  return parse(substituted);
}

function parse(policy: string): Policy {
  const directives: Policy = new Map();
  for (const part of policy.split(';')) {
    const [name, ...sources] = part.trim().split(/\s+/).filter(Boolean);
    if (name) directives.set(name.toLowerCase(), sources);
  }
  return directives;
}

const FETCH_FALLBACK: Record<string, string[]> = {
  'worker-src': ['child-src', 'script-src', 'default-src'],
  'script-src': ['default-src'],
  'style-src': ['default-src'],
  'img-src': ['default-src'],
  'font-src': ['default-src'],
  'connect-src': ['default-src'],
  'manifest-src': ['default-src'],
  'object-src': ['default-src'],
  'child-src': ['default-src'],
  'frame-src': ['child-src', 'default-src'],
};

/** Sources that govern `directive`, after CSP fallback; undefined means unrestricted. */
function effective(policy: Policy, directive: string): string[] | undefined {
  for (const name of [directive, ...(FETCH_FALLBACK[directive] ?? [])]) {
    const sources = policy.get(name);
    if (sources) return sources;
  }
  return undefined;
}

function covers(sources: string[], source: string): boolean {
  if (sources.includes(source)) return true;
  // A host source is inside a scheme source of the same scheme (https://x inside https:).
  const scheme = /^([a-z][a-z0-9+.-]*):\/\//.exec(source)?.[1];
  return scheme !== undefined && sources.includes(`${scheme}:`);
}

function allows(sources: string[] | undefined, url: string): boolean {
  if (sources === undefined) return true;
  if (sources.includes("'none'")) return false;
  const target = new URL(url);
  return sources.some((source) => {
    if (source === "'self'") return target.origin === APP_ORIGIN;
    if (/^[a-z][a-z0-9+.-]*:$/.test(source)) return target.protocol === source;
    if (/^https?:\/\//.test(source)) return new URL(source).origin === target.origin;
    return false;
  });
}

/** A request must pass every enforced policy. */
function allowedBy(policies: Policy[], directive: string, url: string): boolean {
  return policies.every((policy) => allows(effective(policy, directive), url));
}

describe.skipIf(!hasSh)('web image CSP against the Caddy edge CSP (B9)', () => {
  it('renders the image connect-src from the same inputs as Caddy', () => {
    const image = imagePolicy(DEPLOYMENT);
    const caddy = caddyPolicy(DEPLOYMENT);

    expect(image.get('connect-src')).toEqual([
      "'self'",
      'https://api.parkio.test',
      'https://media.parkio.test',
      'https://api.maptiler.com',
    ]);
    expect(image.get('connect-src')).toEqual(caddy.get('connect-src'));
  });

  it('is equal to or stricter than Caddy in every directive', () => {
    const image = imagePolicy(DEPLOYMENT);
    const caddy = caddyPolicy(DEPLOYMENT);
    const directives = new Set([...image.keys(), ...caddy.keys()]);

    for (const directive of directives) {
      if (directive === 'upgrade-insecure-requests') {
        expect(image.has(directive), directive).toBe(true);
        continue;
      }
      const imageSources = effective(image, directive);
      const caddySources = effective(caddy, directive);
      if (caddySources === undefined) continue;
      expect(imageSources, `${directive} must be restricted by the image too`).toBeDefined();
      for (const source of imageSources ?? []) {
        expect(covers(caddySources, source), `${directive}: image allows ${source}, Caddy does not`).toBe(true);
      }
    }
  });

  it('lets the SPA reach its API, media and map origins and nothing else, whichever policy applies', () => {
    const image = imagePolicy(DEPLOYMENT);
    const caddy = caddyPolicy(DEPLOYMENT);
    const cases: Record<string, Policy[]> = { edge: [caddy], image: [image], both: [image, caddy] };

    for (const [name, policies] of Object.entries(cases)) {
      for (const url of [
        `${APP_ORIGIN}/api/v1/config`,
        'https://api.parkio.test/api/v1/auth/login',
        'https://media.parkio.test/media/object.jpg',
        'https://api.maptiler.com/tiles/v3/tiles.json',
      ]) {
        expect(allowedBy(policies, 'connect-src', url), `${name}: ${url}`).toBe(true);
      }
      for (const url of ['https://evil.example/collect', 'http://api.parkio.test/api/v1/auth/login']) {
        expect(allowedBy(policies, 'connect-src', url), `${name}: ${url}`).toBe(false);
      }
    }
    expect(effective(image, 'script-src')).toEqual(["'self'"]);
    expect(effective(caddy, 'script-src')).toEqual(["'self'"]);
    expect(effective(image, 'frame-ancestors')).toEqual(["'none'"]);
    expect(effective(image, 'object-src')).toEqual(["'none'"]);
    // The image keeps worker-src stricter than Caddy ('self' without blob:).
    expect(effective(image, 'worker-src')).toEqual(["'self'"]);
  });
});

describe.skipIf(!hasSh)('15-parkio-web-csp.envsh', () => {
  it('builds connect-src from the edge inputs and defaults the map source', () => {
    const run = runEnvsh({ PARKIO_DOMAIN: 'api.parkio.test', PARKIO_MEDIA_DOMAIN: 'media.parkio.test' });

    expect(run.status).toBe(0);
    expect(run.stdout).toBe("'self' https://api.parkio.test https://media.parkio.test https://api.maptiler.com");
  });

  it('uses an explicit connect-src as given for a run without the edge', () => {
    const run = runEnvsh({ PARKIO_WEB_CSP_CONNECT_SRC: "'self' http://localhost:8080" });

    expect(run.status).toBe(0);
    expect(run.stdout).toBe("'self' http://localhost:8080");
  });

  it.each([
    [{}, 'set PARKIO_DOMAIN and PARKIO_MEDIA_DOMAIN'],
    [{ PARKIO_DOMAIN: 'api.parkio.test' }, 'set PARKIO_DOMAIN and PARKIO_MEDIA_DOMAIN'],
    [{ PARKIO_DOMAIN: 'api.parkio.test; script-src *', PARKIO_MEDIA_DOMAIN: 'media.parkio.test' }, 'PARKIO_DOMAIN is not a host name'],
    [{ PARKIO_DOMAIN: 'api.parkio.test', PARKIO_MEDIA_DOMAIN: 'https://media.parkio.test' }, 'PARKIO_MEDIA_DOMAIN is not a host name'],
    [
      { PARKIO_DOMAIN: 'api.parkio.test', PARKIO_MEDIA_DOMAIN: 'media.parkio.test', PARKIO_MAP_CONNECT_SRC: 'http://tiles.example' },
      'PARKIO_MAP_CONNECT_SRC entries must be https://host sources',
    ],
    [{ PARKIO_WEB_CSP_CONNECT_SRC: "'self'; script-src *" }, 'PARKIO_WEB_CSP_CONNECT_SRC must be space-separated CSP sources'],
  ])('stops the container for %j', (env, message) => {
    const run = runEnvsh(env as Record<string, string>);

    expect(run.status).not.toBe(0);
    expect(run.stderr).toContain(message);
  });
});
