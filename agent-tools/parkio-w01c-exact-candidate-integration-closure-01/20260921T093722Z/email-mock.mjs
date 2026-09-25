import http from 'node:http';
import { writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const port = Number(process.env.MOCK_PORT || 18080);
const statePath = process.env.MOCK_STATE || resolve('mock-state.json');

const state = {
  mode: 'ok', // ok | fail
  sends: 0,
  lastMeta: null,
};

function redact(body) {
  const copy = { ...body };
  if (Array.isArray(copy.to)) copy.to = copy.to.map(() => '[redacted-email]');
  if (typeof copy.text === 'string') {
    copy.text = copy.text
      .replace(/token=[^&\s]+/g, 'token=[redacted]')
      .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, '[redacted-email]');
  }
  return copy;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', `http://127.0.0.1:${port}`);
  if (req.method === 'POST' && url.pathname === '/__mock/mode') {
    let raw = '';
    for await (const chunk of req) raw += chunk;
    const body = JSON.parse(raw || '{}');
    state.mode = body.mode === 'fail' ? 'fail' : 'ok';
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ mode: state.mode }));
    return;
  }
  if (req.method === 'GET' && url.pathname === '/__mock/tokens') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(
      JSON.stringify({
        confirmToken: state.lastMeta?._confirmToken || null,
        withdrawToken: state.lastMeta?._withdrawToken || null,
        sends: state.sends,
      }),
    );
    return;
  }
  if (req.method === 'GET' && url.pathname === '/__mock/state') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ mode: state.mode, sends: state.sends, lastMeta: state.lastMeta }));
    return;
  }
  if (req.method === 'POST' && url.pathname === '/emails') {
    let raw = '';
    for await (const chunk of req) raw += chunk;
    const body = JSON.parse(raw || '{}');
    const text = String(body.text || '');
    const confirm = (text.match(/token=([^\s&]+)/) || [])[1] || null;
    const withdrawMatches = [...text.matchAll(/token=([^\s&]+)/g)].map((m) => m[1]);
    state.sends += 1;
    state.lastMeta = {
      subjectPresent: Boolean(body.subject),
      localeHint: /onaylayın|doğrulamanız/i.test(text) ? 'tr' : 'en',
      confirmTokenPresent: Boolean(confirm),
      withdrawTokenPresent: withdrawMatches.length > 1,
      // Tokens retained only in process memory for the acceptance harness — never written to evidence.
      _confirmToken: confirm,
      _withdrawToken: withdrawMatches.length > 1 ? withdrawMatches[1] : withdrawMatches[0],
      redactedBody: redact(body),
    };
    writeFileSync(
      statePath,
      JSON.stringify({ mode: state.mode, sends: state.sends, lastMeta: { ...state.lastMeta, _confirmToken: undefined, _withdrawToken: undefined } }, null, 2),
    );
    if (state.mode === 'fail') {
      res.writeHead(503, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ message: 'mock provider unavailable' }));
      return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ id: `mock_${state.sends}` }));
    return;
  }
  res.writeHead(404);
  res.end('not found');
});

server.listen(port, '127.0.0.1', () => {
  process.stdout.write(`email_mock=http://127.0.0.1:${port}\n`);
});

export function getState() {
  return state;
}
