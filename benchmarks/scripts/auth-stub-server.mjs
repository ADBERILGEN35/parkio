/**
 * Local synthetic auth stub for k6 engine regression.
 * Modes: positive | refresh_fail | login_fail
 * No production contact.
 */
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export function startAuthStub({ mode = 'positive', host = '0.0.0.0', port = 0 } = {}) {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url || '/', `http://${host}`);
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
    });
    req.on('end', () => {
      const client = String(req.headers['x-parkio-client'] || '');
      const json = (status, payload) => {
        const raw = JSON.stringify(payload);
        res.writeHead(status, {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(raw),
        });
        res.end(raw);
      };

      if (req.method === 'POST' && url.pathname === '/api/v1/auth/login') {
        if (mode === 'login_fail') {
          return json(401, { error: 'invalid_credentials' });
        }
        if (client !== 'mobile') {
          return json(403, { error: 'origin_required_or_client_mismatch' });
        }
        return json(200, {
          accessToken: 'stub-access-1',
          refreshToken: 'stub-refresh-1',
          user: { id: 'stub-user-1' },
        });
      }

      if (req.method === 'POST' && url.pathname === '/api/v1/auth/refresh-token') {
        if (mode === 'refresh_fail') {
          return json(401, { error: 'invalid_refresh' });
        }
        if (client !== 'mobile') {
          return json(403, { error: 'origin_required_or_client_mismatch' });
        }
        let parsed = {};
        try {
          parsed = JSON.parse(body || '{}');
        } catch {
          parsed = {};
        }
        if (!parsed.refreshToken) {
          return json(401, { error: 'missing_refresh' });
        }
        return json(200, {
          accessToken: 'stub-access-2',
          refreshToken: 'stub-refresh-2',
        });
      }

      if (req.method === 'POST' && url.pathname === '/api/v1/auth/logout') {
        return json(204, {});
      }

      return json(404, { error: 'not_found' });
    });
  });

  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      const address = server.address();
      resolve({
        server,
        host,
        port: address.port,
        baseUrl: `http://127.0.0.1:${address.port}`,
        close: () =>
          new Promise((resClose, rejClose) => {
            server.close((err) => (err ? rejClose(err) : resClose()));
          }),
      });
    });
  });
}

const isMain =
  process.argv[1] &&
  path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));
if (isMain) {
  const mode = process.env.PARKIO_STUB_MODE || 'positive';
  const port = Number(process.env.PARKIO_STUB_PORT || '18080');
  const stub = await startAuthStub({ mode, host: '0.0.0.0', port });
  console.log(`[auth-stub] mode=${mode} listening port=${stub.port}`);
  // Keep alive for docker -d usage.
  await new Promise(() => {});
}
