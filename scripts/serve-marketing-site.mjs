#!/usr/bin/env node

import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const root = resolve(scriptDir, '..', 'web', 'marketing');
const portArg = process.argv.indexOf('--port');
const port = portArg >= 0 ? Number(process.argv[portArg + 1]) : 5194;

if (!Number.isInteger(port) || port < 0 || port > 65535) {
  throw new Error('Expected --port to be an integer between 0 and 65535.');
}

const contentTypes = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.png', 'image/png'],
  ['.txt', 'text/plain; charset=utf-8'],
  ['.webmanifest', 'application/manifest+json; charset=utf-8'],
  ['.xml', 'application/xml; charset=utf-8'],
]);

function resolveRequestPath(pathname) {
  const decoded = decodeURIComponent(pathname);
  const normalized = normalize(decoded).replace(/^[/\\]+/, '');
  const candidate = resolve(root, normalized || 'index.html');

  if (candidate !== root && !candidate.startsWith(`${root}${sep}`)) {
    return null;
  }

  if (existsSync(candidate) && statSync(candidate).isDirectory()) {
    return join(candidate, 'index.html');
  }

  return candidate;
}

const server = createServer((request, response) => {
  const url = new URL(request.url ?? '/', 'http://127.0.0.1');
  const requested = resolveRequestPath(url.pathname);
  const found = requested && existsSync(requested) && statSync(requested).isFile();
  const filePath = found ? requested : join(root, '404.html');

  response.statusCode = found ? 200 : 404;
  response.setHeader('Content-Type', contentTypes.get(extname(filePath)) ?? 'application/octet-stream');
  response.setHeader('X-Content-Type-Options', 'nosniff');
  createReadStream(filePath).pipe(response);
});

server.listen(port, '127.0.0.1', () => {
  const address = server.address();
  const boundPort = typeof address === 'object' && address ? address.port : port;
  process.stdout.write(`marketing_server=http://127.0.0.1:${boundPort}\n`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
