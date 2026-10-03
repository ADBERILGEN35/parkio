// The build replaces the placeholder with a digest of the release's files
// (vite-plugins/serviceWorkerVersion.ts), so every release installs a new worker with its own
// cache and the activate step drops the previous release's cache (CL-F39.3).
const CACHE_VERSION = '__PARKIO_SW_VERSION__';
const CACHE_NAME = `parkio-app-shell-${CACHE_VERSION}`;
const APP_SHELL = ['/', '/offline.html', '/manifest.webmanifest', '/icons/parkio-icon.svg', '/icons/parkio-maskable.svg'];

const SENSITIVE_PATH = /\/(api|auth|login|logout|refresh|reset-password|verify-email)(\/|$)/i;

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key)))),
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  const isSameOrigin = url.origin === self.location.origin;

  if (SENSITIVE_PATH.test(url.pathname) || request.headers.has('authorization')) {
    event.respondWith(fetch(request));
    return;
  }

  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match('/offline.html')));
    return;
  }

  // The manifest is not content-hashed: network first, cached copy only offline.
  if (isSameOrigin && url.pathname === '/manifest.webmanifest') {
    event.respondWith(
      fetch(request)
        .then(async (response) => {
          if (response.ok) await (await caches.open(CACHE_NAME)).put(request, response.clone());
          return response;
        })
        .catch(() => caches.match(request)),
    );
    return;
  }

  // Only plain paths are cached, so the cache holds at most the files of one release.
  const cacheableStatic =
    isSameOrigin &&
    url.search === '' &&
    (url.pathname.startsWith('/assets/') || url.pathname.startsWith('/icons/'));

  if (!cacheableStatic) return;

  event.respondWith(
    caches.open(CACHE_NAME).then(async (cache) => {
      const cached = await cache.match(request);
      if (cached) return cached;
      const response = await fetch(request);
      if (response.ok) await cache.put(request, response.clone());
      return response;
    }),
  );
});
