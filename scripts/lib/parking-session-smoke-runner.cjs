'use strict';

const { randomUUID } = require('node:crypto');
const { mkdirSync, writeFileSync } = require('node:fs');
const { join } = require('node:path');
const {
  CLIENT_HEADER,
  redactSecrets,
  shortId,
  resolveSmokeConfig,
} = require('./parking-session-smoke-config.cjs');

const RATE_LIMIT_BACKOFF_MS = [250, 500, 1000];

function newCheck(id, name) {
  return {
    id,
    name,
    status: 'PENDING',
    httpStatus: null,
    detail: null,
    startedAt: null,
    endedAt: null,
    elapsedMs: null,
  };
}

class SmokeRunner {
  /**
   * @param {ReturnType<typeof resolveSmokeConfig>} config
   * @param {{ fetchImpl?: typeof fetch, now?: () => Date, log?: (line: string) => void, sleep?: (ms: number) => Promise<void> }} [opts]
   */
  constructor(config, opts = {}) {
    this.config = config;
    this.fetchImpl = opts.fetchImpl || fetch;
    this.now = opts.now || (() => new Date());
    this.log = opts.log || ((line) => console.log(line));
    this.sleep = opts.sleep || ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
    this.hasMadeRequest = false;
    this.checks = [];
    this.secrets = [config.passwordA, config.passwordB, config.emailA, config.emailB].filter(Boolean);
    this.tokens = [];
    this.ownedSessionIds = new Set();
    this.cleanup = { status: 'NOT_RUN', detail: null };
    this.startedAt = null;
    this.endedAt = null;
  }

  #trackSecret(value) {
    if (value && !this.secrets.includes(value)) this.secrets.push(value);
  }

  safeLog(line) {
    this.log(redactSecrets(line, this.secrets.concat(this.tokens)));
  }

  async request(method, path, { token, body, idempotencyKey, rawPath = false } = {}) {
    const url = rawPath ? path : `${this.config.apiBase}${path}`;
    const headers = {
      'X-Parkio-Client': CLIENT_HEADER,
      Accept: 'application/json',
    };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (this.hasMadeRequest && this.config.requestDelayMs > 0) {
      await this.sleep(this.config.requestDelayMs);
    }
    this.hasMadeRequest = true;

    for (let attempt = 0; ; attempt += 1) {
      const res = await this.fetchImpl(url, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await res.text();
      let json = null;
      if (text) {
        try {
          json = JSON.parse(text);
        } catch {
          json = null;
        }
      }
      const result = {
        status: res.status,
        headers: res.headers,
        text,
        json,
        cacheControl: res.headers.get('cache-control'),
      };
      if (res.status !== 429 || attempt >= RATE_LIMIT_BACKOFF_MS.length) {
        return result;
      }
      await this.sleep(RATE_LIMIT_BACKOFF_MS[attempt]);
    }
  }

  async runCheck(id, name, fn) {
    const check = newCheck(id, name);
    check.startedAt = this.now().toISOString();
    const t0 = Date.now();
    this.checks.push(check);
    try {
      const result = await fn(check);
      if (check.status === 'PENDING') {
        check.status = result?.status || 'PASS';
      }
      if (result?.httpStatus != null) check.httpStatus = result.httpStatus;
      if (result?.detail) check.detail = result.detail;
    } catch (err) {
      check.status = 'FAIL';
      check.detail = redactSecrets(err?.message || String(err), this.secrets.concat(this.tokens));
    }
    check.endedAt = this.now().toISOString();
    check.elapsedMs = Date.now() - t0;
    this.safeLog(
      `${check.status}: ${check.id} ${check.name}` +
        (check.httpStatus != null ? ` (http ${check.httpStatus})` : '') +
        (check.detail ? ` - ${check.detail}` : ''),
    );
    return check;
  }

  async login(alias, email, password) {
    const res = await this.request('POST', '/auth/login', {
      body: { email, password },
    });
    if (res.status !== 200 || !res.json?.accessToken) {
      throw new Error(`${alias} login failed (http ${res.status})`);
    }
    this.#trackSecret(res.json.accessToken);
    if (res.json.refreshToken) this.#trackSecret(res.json.refreshToken);
    this.tokens.push(res.json.accessToken);
    return res.json.accessToken;
  }

  assertNoStore(cacheControl) {
    if (!cacheControl || !String(cacheControl).toLowerCase().includes('no-store')) {
      throw new Error(`expected Cache-Control: no-store, got ${cacheControl || '(missing)'}`);
    }
  }

  async getActive(token) {
    return this.request('GET', '/parking/sessions/active', { token });
  }

  async startSession(token, idempotencyKey) {
    return this.request('POST', '/parking/sessions', {
      token,
      idempotencyKey,
      body: { latitude: this.config.lat, longitude: this.config.lng },
    });
  }

  async completeSession(token, sessionId, idempotencyKey) {
    return this.request('POST', `/parking/sessions/${sessionId}/complete`, {
      token,
      idempotencyKey,
    });
  }

  async cancelSession(token, sessionId, idempotencyKey) {
    return this.request('POST', `/parking/sessions/${sessionId}/cancel`, {
      token,
      idempotencyKey,
    });
  }

  async history(token, { size = 20, cursor } = {}) {
    const q = new URLSearchParams({ size: String(size) });
    if (cursor) q.set('cursor', cursor);
    return this.request('GET', `/parking/sessions/history?${q}`, { token });
  }

  async deleteSession(token, sessionId) {
    return this.request('DELETE', `/parking/sessions/${sessionId}`, { token });
  }

  async deleteHistory(token) {
    return this.request('DELETE', '/parking/sessions/history', { token });
  }

  async reconcileActive(token) {
    const active = await this.getActive(token);
    if (active.status === 204) return null;
    if (active.status === 200 && active.json?.id) {
      const id = active.json.id;
      this.ownedSessionIds.add(id);
      const cancel = await this.cancelSession(token, id, randomUUID());
      if (cancel.status !== 200) {
        throw new Error(`reconcile cancel failed (http ${cancel.status})`);
      }
      return id;
    }
    throw new Error(`unexpected active status ${active.status}`);
  }

  async runCleanup(tokenA, tokenB) {
    const notes = [];
    let cleanupOk = true;
    const attempt = async (label, action, acceptedStatuses) => {
      try {
        const result = await action();
        notes.push(`${label} http ${result.status}`);
        if (!acceptedStatuses.includes(result.status)) cleanupOk = false;
        return result;
      } catch (err) {
        cleanupOk = false;
        notes.push(`${label} error ${redactSecrets(err?.message || String(err), this.secrets.concat(this.tokens))}`);
        return null;
      }
    };

    if (tokenA) {
      const active = await attempt('userA active', () => this.getActive(tokenA), [200, 204]);
      if (active?.status === 200 && active.json?.id) {
        await attempt(
          `userA active cancel ${shortId(active.json.id)}`,
          () => this.cancelSession(tokenA, active.json.id, randomUUID()),
          [200],
        );
      }
      // Prefer single-session deletes for owned ids; bulk is still attempted even if one delete fails.
      for (const id of [...this.ownedSessionIds]) {
        await attempt(`userA delete ${shortId(id)}`, () => this.deleteSession(tokenA, id), [204]);
      }
      await attempt('userA bulk-history', () => this.deleteHistory(tokenA), [204]);
    }
    if (tokenB) {
      const active = await attempt('userB active', () => this.getActive(tokenB), [200, 204]);
      if (active?.status === 200 && active.json?.id) {
        await attempt(
          `userB active cancel ${shortId(active.json.id)}`,
          () => this.cancelSession(tokenB, active.json.id, randomUUID()),
          [200],
        );
      }
      await attempt('userB bulk-history', () => this.deleteHistory(tokenB), [204]);
    }
    if (tokenA) {
      const hist = await attempt('userA history verify', () => this.history(tokenA, { size: 1 }), [200]);
      const remaining = hist?.status === 200 && Array.isArray(hist.json?.items) ? hist.json.items.length : -1;
      notes.push(`userA historyRemaining=${remaining}`);
      if (remaining !== 0) cleanupOk = false;
    }

    this.cleanup = {
      status: cleanupOk ? 'PASS' : 'FAIL',
      detail: notes.join('; ') + (cleanupOk ? '' : '; cleanup incomplete'),
    };
  }

  async run() {
    this.startedAt = this.now().toISOString();
    this.safeLog(`=== ParkingSession hosted smoke runId=${this.config.runId} profile=${this.config.profile} ===`);

    let tokenA = null;
    let tokenB = null;
    let cleanupActiveSucceeded = false;

    try {
      await this.runCheck('PS-HB-01', 'target safety validation', async (c) => {
        c.detail = `profile=${this.config.profile}; host=${new URL(this.config.baseUrl).hostname}`;
        return { status: 'PASS' };
      });

      await this.runCheck('PS-HB-02', 'gateway health', async (c) => {
        const res = await this.request('GET', `${this.config.baseUrl}/actuator/health`, {
          rawPath: true,
        });
        c.httpStatus = res.status;
        if (res.status !== 200) throw new Error('gateway health not 200');
        return { status: 'PASS', httpStatus: res.status };
      });

      await this.runCheck('PS-HB-03', 'authenticate User A', async (c) => {
        tokenA = await this.login('userA', this.config.emailA, this.config.passwordA);
        c.detail = 'alias=userA';
        return { status: 'PASS', httpStatus: 200 };
      });

      await this.runCheck('PS-HB-04', 'initial cleanup/reconciliation', async (c) => {
        const cleared = await this.reconcileActive(tokenA);
        c.detail = cleared ? `cancelled residue ${shortId(cleared)}` : 'no active residue';
        return { status: 'PASS' };
      });

      // Capability probe: opaque foreign DELETE must be 204 when S1-P0-07 is deployed.
      let deleteCapable = false;
      await this.runCheck('PS-HB-04b', 'deletion API capability probe', async (c) => {
        const probe = await this.deleteSession(tokenA, randomUUID());
        c.httpStatus = probe.status;
        if (probe.status === 204) {
          deleteCapable = true;
          c.detail = 'opaque 204 - delete endpoints available';
          return { status: 'PASS', httpStatus: 204 };
        }
        c.detail = `expected 204 for foreign UUID, got ${probe.status} (deployed image likely lacks S1-P0-07)`;
        return { status: 'FAIL', httpStatus: probe.status };
      });

      const startKey1 = randomUUID();
      let session1 = null;

      await this.runCheck('PS-HB-05', 'start session', async (c) => {
        const res = await this.startSession(tokenA, startKey1);
        c.httpStatus = res.status;
        this.assertNoStore(res.cacheControl);
        if (res.status !== 201 || !res.json?.id || res.json.status !== 'ACTIVE') {
          throw new Error(`start failed status=${res.json?.status}`);
        }
        if (res.json.parkingSource && res.json.parkingSource !== 'MANUAL') {
          throw new Error(`expected MANUAL source, got ${res.json.parkingSource}`);
        }
        session1 = res.json;
        this.ownedSessionIds.add(session1.id);
        c.detail = `session=${shortId(session1.id)}`;
        return { status: 'PASS', httpStatus: 201 };
      });

      await this.runCheck('PS-HB-06', 'start idempotent replay', async (c) => {
        const res = await this.startSession(tokenA, startKey1);
        c.httpStatus = res.status;
        if (![200, 201].includes(res.status) || res.json?.id !== session1.id) {
          throw new Error(`replay did not return same session (http ${res.status})`);
        }
        return { status: 'PASS', httpStatus: res.status, detail: 'same session id' };
      });

      await this.runCheck('PS-HB-07', 'active read', async (c) => {
        const res = await this.getActive(tokenA);
        c.httpStatus = res.status;
        this.assertNoStore(res.cacheControl);
        if (res.status !== 200 || res.json?.id !== session1.id || res.json?.status !== 'ACTIVE') {
          throw new Error('active read mismatch');
        }
        return { status: 'PASS', httpStatus: 200 };
      });

      await this.runCheck('PS-HB-08', 'second-start conflict', async (c) => {
        const res = await this.startSession(tokenA, randomUUID());
        c.httpStatus = res.status;
        if (res.status !== 409) throw new Error(`expected 409, got ${res.status}`);
        const active = await this.getActive(tokenA);
        if (active.json?.id !== session1.id) throw new Error('active session changed after conflict');
        return { status: 'PASS', httpStatus: 409, detail: res.json?.code || 'conflict' };
      });

      await this.runCheck('PS-HB-09', 'complete', async (c) => {
        const res = await this.completeSession(tokenA, session1.id, randomUUID());
        c.httpStatus = res.status;
        this.assertNoStore(res.cacheControl);
        if (res.status !== 200 || res.json?.status !== 'COMPLETED' || !res.json?.endedAt) {
          throw new Error('complete failed');
        }
        session1 = res.json;
        return { status: 'PASS', httpStatus: 200 };
      });

      await this.runCheck('PS-HB-10', 'active absent after complete', async (c) => {
        const res = await this.getActive(tokenA);
        c.httpStatus = res.status;
        if (res.status !== 204) throw new Error(`expected 204, got ${res.status}`);
        return { status: 'PASS', httpStatus: 204 };
      });

      await this.runCheck('PS-HB-11', 'completed history', async (c) => {
        const res = await this.history(tokenA, { size: this.config.pageSize });
        c.httpStatus = res.status;
        this.assertNoStore(res.cacheControl);
        const items = res.json?.items || [];
        const found = items.find((i) => i.id === session1.id);
        if (!found || found.status !== 'COMPLETED') throw new Error('completed session missing from history');
        if (items.some((i) => i.status === 'ACTIVE')) throw new Error('ACTIVE leaked into history');
        return { status: 'PASS', httpStatus: 200, detail: `pageSize=${items.length}` };
      });

      if (deleteCapable) {
        await this.runCheck('PS-HB-12', 'single delete', async (c) => {
          const res = await this.deleteSession(tokenA, session1.id);
          c.httpStatus = res.status;
          if (res.status !== 204) throw new Error(`expected 204, got ${res.status}`);
          return { status: 'PASS', httpStatus: 204 };
        });

        await this.runCheck('PS-HB-13', 'repeated single delete', async (c) => {
          const res = await this.deleteSession(tokenA, session1.id);
          c.httpStatus = res.status;
          if (res.status !== 204) throw new Error(`expected 204, got ${res.status}`);
          const hist = await this.history(tokenA, { size: 50 });
          if ((hist.json?.items || []).some((i) => i.id === session1.id)) {
            throw new Error('deleted session still in history');
          }
          return { status: 'PASS', httpStatus: 204 };
        });
      } else {
        await this.runCheck('PS-HB-12', 'single delete', async () => ({
          status: 'FAIL',
          detail: 'skipped - deletion API not healthy on deployed revision',
        }));
        await this.runCheck('PS-HB-13', 'repeated single delete', async () => ({
          status: 'FAIL',
          detail: 'skipped - deletion API not healthy on deployed revision',
        }));
      }

      const startKey2 = randomUUID();
      let session2 = null;
      await this.runCheck('PS-HB-14', 'start second session', async (c) => {
        const res = await this.startSession(tokenA, startKey2);
        c.httpStatus = res.status;
        if (res.status !== 201 || !res.json?.id) throw new Error('second start failed');
        session2 = res.json;
        this.ownedSessionIds.add(session2.id);
        return { status: 'PASS', httpStatus: 201, detail: `session=${shortId(session2.id)}` };
      });

      await this.runCheck('PS-HB-15', 'cancel', async (c) => {
        const res = await this.cancelSession(tokenA, session2.id, randomUUID());
        c.httpStatus = res.status;
        if (res.status !== 200 || res.json?.status !== 'CANCELLED') throw new Error('cancel failed');
        session2 = res.json;
        const active = await this.getActive(tokenA);
        if (active.status !== 204) throw new Error('active still present after cancel');
        return { status: 'PASS', httpStatus: 200 };
      });

      await this.runCheck('PS-HB-16', 'cancelled history', async (c) => {
        const res = await this.history(tokenA, { size: 50 });
        c.httpStatus = res.status;
        const found = (res.json?.items || []).find((i) => i.id === session2.id);
        if (!found || found.status !== 'CANCELLED') throw new Error('cancelled session missing');
        return { status: 'PASS', httpStatus: 200 };
      });

      if (deleteCapable) {
        await this.runCheck('PS-HB-17', 'bulk delete', async (c) => {
          const res = await this.deleteHistory(tokenA);
          c.httpStatus = res.status;
          if (res.status !== 204) throw new Error(`expected 204, got ${res.status}`);
          return { status: 'PASS', httpStatus: 204 };
        });

        await this.runCheck('PS-HB-18', 'repeated bulk delete', async (c) => {
          const res = await this.deleteHistory(tokenA);
          c.httpStatus = res.status;
          if (res.status !== 204) throw new Error(`expected 204, got ${res.status}`);
          const hist = await this.history(tokenA, { size: 20 });
          if ((hist.json?.items || []).length !== 0) throw new Error('history not empty after bulk delete');
          return { status: 'PASS', httpStatus: 204 };
        });
      } else {
        await this.runCheck('PS-HB-17', 'bulk delete', async () => ({
          status: 'FAIL',
          detail: 'skipped - deletion API not healthy on deployed revision',
        }));
        await this.runCheck('PS-HB-18', 'repeated bulk delete', async () => ({
          status: 'FAIL',
          detail: 'skipped - deletion API not healthy on deployed revision',
        }));
      }

      // ACTIVE preservation + ACTIVE delete conflict
      let preserveSession = null;
      let terminalForPreserve = null;

      await this.runCheck('PS-HB-19', 'active-preservation setup', async (c) => {
        // Ensure at least one terminal exists, then start ACTIVE
        const tStart = await this.startSession(tokenA, randomUUID());
        if (tStart.status !== 201) throw new Error('terminal seed start failed');
        terminalForPreserve = tStart.json;
        this.ownedSessionIds.add(terminalForPreserve.id);
        const completed = await this.completeSession(tokenA, terminalForPreserve.id, randomUUID());
        if (completed.status !== 200) throw new Error('terminal seed complete failed');

        const aStart = await this.startSession(tokenA, randomUUID());
        if (aStart.status !== 201) throw new Error('active preserve start failed');
        preserveSession = aStart.json;
        this.ownedSessionIds.add(preserveSession.id);
        c.detail = `active=${shortId(preserveSession.id)}`;
        return { status: 'PASS', httpStatus: 201 };
      });

      if (deleteCapable) {
        await this.runCheck('PS-HB-20', 'active single-delete conflict', async (c) => {
          const res = await this.deleteSession(tokenA, preserveSession.id);
          c.httpStatus = res.status;
          if (res.status !== 409 || res.json?.code !== 'PARKING_SESSION_NOT_TERMINAL') {
            throw new Error(`expected 409 PARKING_SESSION_NOT_TERMINAL, got ${res.status} ${res.json?.code}`);
          }
          const active = await this.getActive(tokenA);
          if (active.json?.id !== preserveSession.id || active.json?.status !== 'ACTIVE') {
            throw new Error(
              `expected ACTIVE session: ${preserveSession.id}; ` +
              `actual ACTIVE session: ${active.json?.id || '(none)'}; ` +
              `expected status: ACTIVE; actual status: ${active.json?.status || `(http ${active.status})`}`,
            );
          }
          return { status: 'PASS', httpStatus: 409 };
        });

        await this.runCheck('PS-HB-21', 'delete-all preserves ACTIVE', async (c) => {
          const res = await this.deleteHistory(tokenA);
          c.httpStatus = res.status;
          if (res.status !== 204) throw new Error(`bulk delete http ${res.status}`);
          const active = await this.getActive(tokenA);
          if (active.status !== 200 || active.json?.id !== preserveSession.id || active.json?.status !== 'ACTIVE') {
            throw new Error('ACTIVE not preserved after delete-all');
          }
          const hist = await this.history(tokenA, { size: 20 });
          if ((hist.json?.items || []).length !== 0) throw new Error('terminal history not empty');
          return { status: 'PASS', httpStatus: 204 };
        });
      } else {
        await this.runCheck('PS-HB-20', 'active single-delete conflict', async () => ({
          status: 'FAIL',
          detail: 'skipped - deletion API not healthy on deployed revision',
        }));
        await this.runCheck('PS-HB-21', 'delete-all preserves ACTIVE', async () => ({
          status: 'FAIL',
          detail: 'skipped - deletion API not healthy on deployed revision',
        }));
      }

      await this.runCheck('PS-HB-22', 'cleanup active', async (c) => {
        if (!preserveSession?.id) throw new Error('no active to cleanup');
        const res = await this.cancelSession(tokenA, preserveSession.id, randomUUID());
        c.httpStatus = res.status;
        if (res.status !== 200) throw new Error('cleanup cancel failed');
        cleanupActiveSucceeded = true;
        return { status: 'PASS', httpStatus: 200 };
      });

      if (this.config.hasUserB) {
        await this.runCheck('PS-HB-23', 'owner isolation', async (c) => {
          tokenB = await this.login('userB', this.config.emailB, this.config.passwordB);
          const aStart = await this.startSession(tokenA, randomUUID());
          if (aStart.status !== 201) throw new Error('userA isolation start failed');
          const owned = aStart.json;
          this.ownedSessionIds.add(owned.id);
          await this.completeSession(tokenA, owned.id, randomUUID());

          const bHist = await this.history(tokenB, { size: 50 });
          if ((bHist.json?.items || []).some((i) => i.id === owned.id)) {
            throw new Error('userB history leaked userA session');
          }
          if (deleteCapable) {
            const foreignDelete = await this.deleteSession(tokenB, owned.id);
            if (foreignDelete.status !== 204) throw new Error(`foreign delete expected 204, got ${foreignDelete.status}`);
            const aHist = await this.history(tokenA, { size: 50 });
            if (!(aHist.json?.items || []).some((i) => i.id === owned.id)) {
              throw new Error('userA session vanished after userB opaque delete');
            }
          }
          c.detail = deleteCapable ? 'history+opaque-delete isolation' : 'history isolation only';
          return { status: 'PASS', httpStatus: 200 };
        });
      } else {
        await this.runCheck('PS-HB-23', 'owner isolation', async () => ({
          status: 'NOT_EXECUTED',
          detail: 'User B credentials not provided',
        }));
      }

      // Cursor pagination with page size 1 - only if we can create/delete terminals safely
      await this.runCheck('PS-HB-23b', 'cursor pagination (page size 1)', async (c) => {
        if (!deleteCapable) {
          return { status: 'NOT_EXECUTED', detail: 'requires healthy deletion for cleanup' };
        }
        const ids = [];
        for (let i = 0; i < 2; i += 1) {
          const s = await this.startSession(tokenA, randomUUID());
          if (s.status !== 201 && !cleanupActiveSucceeded) {
            return {
              status: 'SKIP',
              httpStatus: s.status,
              detail: 'SKIPPED (blocked by cleanup)',
            };
          }
          if (s.status !== 201) throw new Error(`pagination seed start failed (http ${s.status})`);
          ids.push(s.json.id);
          this.ownedSessionIds.add(s.json.id);
          await this.completeSession(tokenA, s.json.id, randomUUID());
        }
        const page1 = await this.history(tokenA, { size: 1 });
        if (!page1.json?.items?.length || !page1.json.nextCursor) {
          throw new Error('expected nextCursor with size=1 and 2 terminals');
        }
        const page2 = await this.history(tokenA, { size: 1, cursor: page1.json.nextCursor });
        const id1 = page1.json.items[0].id;
        const id2 = page2.json.items?.[0]?.id;
        if (!id2 || id1 === id2) throw new Error('duplicate or missing page-2 id');
        c.detail = 'two pages, no duplicate ids';
        return { status: 'PASS', httpStatus: 200 };
      });

      await this.runCheck('PS-HB-24', 'outbox/relay evidence', async () => ({
        status: this.config.observeEvents ? 'FAIL' : 'NOT_OBSERVABLE',
        detail: this.config.observeEvents
          ? 'PARKIO_SMOKE_OBSERVE_EVENTS=1 but no safe hosted ops endpoint configured'
          : 'no public outbox verification endpoint; covered by parking-service unit/IT',
      }));

      await this.runCheck('PS-HB-25', 'analytics ingestion evidence', async () => ({
        status: this.config.observeEvents ? 'FAIL' : 'NOT_OBSERVABLE',
        detail: this.config.observeEvents
          ? 'PARKIO_SMOKE_OBSERVE_EVENTS=1 but no safe analytics verification endpoint configured'
          : 'no public analytics query for lifecycle facts; covered by analytics-service IT',
      }));
    } finally {
      await this.runCleanup(tokenA, tokenB);
      await this.runCheck('PS-HB-26', 'final cleanup verification', async (c) => {
        if (!tokenA) return { status: 'FAIL', detail: 'no token for cleanup verify' };
        const active = await this.getActive(tokenA);
        c.httpStatus = active.status;
        if (active.status !== 204) {
          throw new Error(`ACTIVE still present after cleanup (http ${active.status})`);
        }
        if (this.cleanup.status !== 'PASS') {
          return { status: 'FAIL', httpStatus: 204, detail: this.cleanup.detail };
        }
        return { status: 'PASS', httpStatus: 204, detail: this.cleanup.detail };
      });
      this.endedAt = this.now().toISOString();
    }

    return this.summary();
  }

  summary() {
    const counts = { PASS: 0, FAIL: 0, SKIP: 0, NOT_EXECUTED: 0, NOT_OBSERVABLE: 0, PENDING: 0 };
    for (const c of this.checks) {
      counts[c.status] = (counts[c.status] || 0) + 1;
    }
    const failed = counts.FAIL > 0 || this.cleanup.status === 'FAIL';
    return {
      runId: this.config.runId,
      profile: this.config.profile,
      baseHost: new URL(this.config.baseUrl).hostname,
      startedAt: this.startedAt,
      endedAt: this.endedAt,
      totalChecks: this.checks.length,
      counts,
      cleanup: this.cleanup,
      checks: this.checks,
      exitCode: failed ? 1 : 0,
      userAliases: {
        userA: 'userA',
        userB: this.config.hasUserB ? 'userB' : null,
      },
    };
  }
}

function writeEvidence(summary, evidenceDir, secrets = []) {
  mkdirSync(evidenceDir, { recursive: true });
  const stamp = summary.runId;
  const jsonPath = join(evidenceDir, `${stamp}.json`);
  const mdPath = join(evidenceDir, `${stamp}.md`);
  const latestJson = join(evidenceDir, 'latest.json');
  const latestMd = join(evidenceDir, 'latest.md');

  const safe = JSON.parse(redactSecrets(JSON.stringify(summary, null, 2), secrets));
  const json = JSON.stringify(safe, null, 2);
  writeFileSync(jsonPath, json, 'utf8');
  writeFileSync(latestJson, json, 'utf8');

  const md = [
    '# ParkingSession hosted-beta smoke evidence (S1-P0-12 / R27)',
    '',
    `- Run ID: \`${safe.runId}\``,
    `- Profile: \`${safe.profile}\``,
    `- Host: \`${safe.baseHost}\``,
    `- Started (UTC): ${safe.startedAt}`,
    `- Ended (UTC): ${safe.endedAt}`,
    `- Total checks: ${safe.totalChecks}`,
    `- Counts: PASS=${safe.counts.PASS || 0} FAIL=${safe.counts.FAIL || 0} NOT_EXECUTED=${safe.counts.NOT_EXECUTED || 0} NOT_OBSERVABLE=${safe.counts.NOT_OBSERVABLE || 0}`,
    `- Cleanup: ${safe.cleanup?.status} — ${safe.cleanup?.detail || ''}`,
    `- Exit code: ${safe.exitCode}`,
    '',
    '## Checks',
    '',
    '| ID | Name | Status | HTTP | Detail |',
    '|----|------|--------|------|--------|',
    ...safe.checks.map(
      (c) =>
        `| ${c.id} | ${c.name} | ${c.status} | ${c.httpStatus ?? ''} | ${(c.detail || '').replace(/\|/g, '/')} |`,
    ),
    '',
    '## Invocation (secrets redacted)',
    '',
    '```bash',
    'PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta \\',
    'PARKIO_SMOKE_CONFIRM_TARGET=beta \\',
    'PARKIO_SMOKE_DISPOSABLE_ACCOUNT=I_CONFIRM_DISPOSABLE \\',
    'PARKIO_SMOKE_USER_A_EMAIL=<disposable-a> \\',
    'PARKIO_SMOKE_USER_A_PASSWORD=<secret> \\',
    './scripts/smoke-parking-session-hosted-beta.sh',
    '```',
    '',
    '## Limitations',
    '',
    '- Mobile UI was not part of this API smoke.',
    '- Outbox/analytics layers are NOT_OBSERVABLE without private ops access.',
    '- Coordinates, tokens, and passwords are redacted from evidence.',
    '',
  ].join('\n');

  writeFileSync(mdPath, md, 'utf8');
  writeFileSync(latestMd, md, 'utf8');
  return { jsonPath, mdPath, latestJson, latestMd };
}

async function main(env = process.env, opts = {}) {
  const config = resolveSmokeConfig(env);
  if (!config.ok) {
    const lines = ['REFUSING to run ParkingSession hosted smoke:', ...config.errors.map((e) => `  - ${e}`)];
    (opts.log || console.error)(lines.join('\n'));
    return { exitCode: 2, config, summary: null, gateErrors: config.errors };
  }

  const runner = new SmokeRunner(config, opts);
  const summary = await runner.run();
  const paths = writeEvidence(summary, config.evidenceDir, runner.secrets.concat(runner.tokens));
  runner.safeLog(`evidence: ${paths.jsonPath}`);
  runner.safeLog(
    `=== summary pass=${summary.counts.PASS || 0} fail=${summary.counts.FAIL || 0} cleanup=${summary.cleanup.status} exit=${summary.exitCode} ===`,
  );
  return { exitCode: summary.exitCode, config, summary, paths };
}


module.exports = { SmokeRunner, writeEvidence, main };

if (require.main === module) {
  main().then((r) => process.exit(r.exitCode));
}
