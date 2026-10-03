import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import { describe, expect, it } from 'vitest';

/**
 * CL-F39.3: public/sw.js across two builds. The worker runs in a fake service-worker global
 * (Cache Storage, fetch, events) shared by both builds, like a browser that keeps its caches
 * while a new release installs. Builds stamp the version placeholder the way vite.config.ts
 * does; a worker without the placeholder is the same file for every build.
 */

const swSource = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../public/sw.js'),
  'utf8',
);
const ORIGIN = 'https://app.parkio.test';

type Handler = (event: Record<string, unknown>) => void;

class FakeCacheStorage {
  readonly stores = new Map<string, Map<string, Response>>();

  async open(name: string) {
    if (!this.stores.has(name)) this.stores.set(name, new Map());
    const store = this.stores.get(name)!;
    return {
      addAll: async (urls: string[]) => {
        for (const url of urls) {
          store.set(absolute(url), await network(absolute(url)));
        }
      },
      match: async (request: Request | string) => store.get(absolute(request))?.clone(),
      put: async (request: Request | string, response: Response) => {
        store.set(absolute(request), response);
      },
    };
  }

  async keys() {
    return [...this.stores.keys()];
  }

  async delete(name: string) {
    return this.stores.delete(name);
  }

  async match(request: Request | string) {
    for (const store of this.stores.values()) {
      const hit = store.get(absolute(request));
      if (hit) return hit.clone();
    }
    return undefined;
  }

  cachedUrls() {
    return [...this.stores.values()].flatMap((store) => [...store.keys()]).sort();
  }
}

let online = true;
let manifestBody = '{"name":"Parkio","build":"A"}';

function absolute(input: Request | string) {
  return new URL(typeof input === 'string' ? input : input.url, ORIGIN).toString();
}

async function network(input: Request | string): Promise<Response> {
  if (!online) throw new TypeError('offline');
  const url = new URL(absolute(input));
  const body = url.pathname === '/manifest.webmanifest' ? manifestBody : `body of ${url.pathname}`;
  return new Response(body, { status: 200 });
}

/** Installs and activates one build's worker; returns a fetch dispatcher for it. */
async function installBuild(caches: FakeCacheStorage, version: string) {
  const handlers = new Map<string, Handler>();
  const self = {
    location: { origin: ORIGIN },
    addEventListener: (type: string, handler: Handler) => handlers.set(type, handler),
    skipWaiting: () => undefined,
    clients: { claim: () => undefined },
  };
  runInNewContext(swSource.replaceAll('__PARKIO_SW_VERSION__', version), {
    self,
    caches,
    fetch: (input: Request | string) => network(input),
    URL,
    Promise,
    console,
  });
  for (const type of ['install', 'activate']) {
    const pending: Promise<unknown>[] = [];
    handlers.get(type)!({ waitUntil: (promise: Promise<unknown>) => pending.push(promise) });
    await Promise.all(pending);
  }
  return async (path: string, mode: RequestMode = 'no-cors') => {
    let response: Promise<Response | undefined> | undefined;
    handlers.get('fetch')!({
      request: { url: `${ORIGIN}${path}`, method: 'GET', mode, headers: new Headers() },
      respondWith: (promise: Promise<Response | undefined>) => {
        response = promise;
      },
    });
    return response ? await response : await network(path);
  };
}

describe('service worker across releases', () => {
  it('drops the previous build’s hashed assets after an upgrade', async () => {
    online = true;
    const caches = new FakeCacheStorage();
    const buildA = await installBuild(caches, 'build-a');
    await buildA('/assets/app-AAAA1111.js');
    expect(caches.cachedUrls()).toContain(`${ORIGIN}/assets/app-AAAA1111.js`);

    const buildB = await installBuild(caches, 'build-b');
    await buildB('/assets/app-BBBB2222.js');

    expect(caches.cachedUrls()).not.toContain(`${ORIGIN}/assets/app-AAAA1111.js`);
    expect(caches.cachedUrls()).toContain(`${ORIGIN}/assets/app-BBBB2222.js`);
    expect(caches.stores.size).toBe(1);
  });

  it('keeps the offline shell working after an upgrade', async () => {
    online = true;
    const caches = new FakeCacheStorage();
    await installBuild(caches, 'build-a');
    const buildB = await installBuild(caches, 'build-b');

    online = false;
    const page = await buildB('/explore', 'navigate');

    expect(await page!.text()).toBe('body of /offline.html');
    online = true;
  });

  it('caches assets only under their plain path', async () => {
    online = true;
    const caches = new FakeCacheStorage();
    const build = await installBuild(caches, 'build-a');
    await build('/assets/app-AAAA1111.js?v=1');
    await build('/assets/app-AAAA1111.js?v=2');

    expect(caches.cachedUrls().filter((url) => url.includes('/assets/'))).toEqual([]);
  });

  it('serves the current manifest when the network has a newer one', async () => {
    online = true;
    manifestBody = '{"name":"Parkio","build":"A"}';
    const caches = new FakeCacheStorage();
    const buildA = await installBuild(caches, 'build-a');
    await buildA('/manifest.webmanifest');

    manifestBody = '{"name":"Parkio","build":"B"}';
    const response = await buildA('/manifest.webmanifest');

    expect(await response!.text()).toContain('"build":"B"');
    manifestBody = '{"name":"Parkio","build":"A"}';
  });
});
