'use strict';

const { randomUUID } = require('node:crypto');
const { join } = require('node:path');

const CLIENT_HEADER = 'mobile';
const DEFAULT_SMOKE_LAT = 41.0082;
const DEFAULT_SMOKE_LNG = 28.9784;
const BETA_HOSTS = new Set(['api.parkio.dev', 'api.beta.parkio.dev']);

function redactSecrets(text, secrets = []) {
  let out = String(text ?? '');
  for (const secret of secrets) {
    if (!secret || String(secret).length < 4) continue;
    out = out.split(String(secret)).join('[REDACTED]');
  }
  out = out.replace(/Bearer\s+[A-Za-z0-9\-._~+/]+=*/gi, 'Bearer [REDACTED]');
  out = out.replace(/"accessToken"\s*:\s*"[^"]+"/gi, '"accessToken":"[REDACTED]"');
  out = out.replace(/"refreshToken"\s*:\s*"[^"]+"/gi, '"refreshToken":"[REDACTED]"');
  out = out.replace(/"password"\s*:\s*"[^"]+"/gi, '"password":"[REDACTED]"');
  out = out.replace(/"latitude"\s*:\s*-?\d+(\.\d+)?/gi, '"latitude":"[REDACTED]"');
  out = out.replace(/"longitude"\s*:\s*-?\d+(\.\d+)?/gi, '"longitude":"[REDACTED]"');
  return out;
}

function shortId(id) {
  if (!id || typeof id !== 'string') return null;
  return id.length <= 12 ? id : id.slice(0, 8) + '...';
}

function validateCoordinates(lat, lng) {
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return 'coordinates must be finite numbers';
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return 'coordinates out of valid latitude/longitude range';
  return null;
}

function resolveSmokeConfig(env = process.env) {
  const errors = [];
  const profile = env.PARKIO_DEPLOYMENT_PROFILE || '';
  const confirm = env.PARKIO_SMOKE_CONFIRM_TARGET || '';
  const disposable = env.PARKIO_SMOKE_DISPOSABLE_ACCOUNT || '';
  const environment = env.PARKIO_SMOKE_ENVIRONMENT || '';

  let baseUrl = env.PARKIO_SMOKE_BASE_URL || env.PARKIO_GATEWAY_URL || '';
  if (profile === 'azure-hosted-beta') baseUrl = baseUrl || 'https://api.parkio.dev';
  if (profile === 'hosted-beta') baseUrl = baseUrl || 'http://127.0.0.1:8080';

  if (profile !== 'azure-hosted-beta' && profile !== 'hosted-beta') {
    errors.push("PARKIO_DEPLOYMENT_PROFILE must be 'azure-hosted-beta' or 'hosted-beta'");
  }
  if (confirm !== 'beta') errors.push("PARKIO_SMOKE_CONFIRM_TARGET must be exactly 'beta'");
  if (environment && environment !== 'beta') errors.push("PARKIO_SMOKE_ENVIRONMENT must be 'beta' when set");
  if (disposable !== 'I_CONFIRM_DISPOSABLE') {
    errors.push("PARKIO_SMOKE_DISPOSABLE_ACCOUNT must be 'I_CONFIRM_DISPOSABLE' (dedicated smoke account only)");
  }
  if (!baseUrl) errors.push('PARKIO_SMOKE_BASE_URL or PARKIO_GATEWAY_URL is required');

  let url = null;
  try { url = new URL(baseUrl); } catch { errors.push('base URL is not a valid URL'); }

  if (url) {
    const host = url.hostname.toLowerCase();
    if (url.protocol !== 'https:' && url.protocol !== 'http:') errors.push('unsupported URL protocol');
    if (profile === 'azure-hosted-beta') {
      if (url.protocol !== 'https:') errors.push('azure-hosted-beta requires https');
      if (host !== 'api.parkio.dev') errors.push('azure-hosted-beta must target https://api.parkio.dev');
      if (!BETA_HOSTS.has(host)) errors.push('azure-hosted-beta host is not an approved beta host');
    }
    if (profile === 'hosted-beta' && url.protocol === 'http:' && host !== '127.0.0.1' && host !== 'localhost') {
      errors.push('non-local hosted-beta smoke requires https');
    }
    if (host === 'api.parkio.com' || host.endsWith('.parkio.com')) errors.push('refusing production-like host');
  }

  const emailA = env.PARKIO_SMOKE_USER_A_EMAIL || env.PARKIO_REAL_USER_EMAIL || '';
  const passwordA = env.PARKIO_SMOKE_USER_A_PASSWORD || env.PARKIO_REAL_USER_PASSWORD || '';
  if (!emailA || !passwordA) {
    errors.push('User A credentials required (PARKIO_SMOKE_USER_A_EMAIL/PASSWORD or PARKIO_REAL_USER_*)');
  }

  const emailB = env.PARKIO_SMOKE_USER_B_EMAIL || '';
  const passwordB = env.PARKIO_SMOKE_USER_B_PASSWORD || '';
  const hasUserB = Boolean(emailB && passwordB);

  const lat = Number(env.PARKIO_SMOKE_LAT ?? DEFAULT_SMOKE_LAT);
  const lng = Number(env.PARKIO_SMOKE_LNG ?? DEFAULT_SMOKE_LNG);
  const coordError = validateCoordinates(lat, lng);
  if (coordError) errors.push(coordError);

  const runId = env.PARKIO_SMOKE_RUN_ID || ('ps-' + new Date().toISOString().replace(/[:.]/g, '-') + '-' + randomUUID().slice(0, 8));
  const evidenceDir = env.PARKIO_SMOKE_EVIDENCE_DIR || join(process.cwd(), 'docs/evidence/sprint-01/parking-session-hosted-beta');
  const requestDelayMs = Number(env.PARKIO_SMOKE_REQUEST_DELAY_MS ?? 150);
  if (!Number.isFinite(requestDelayMs) || requestDelayMs < 0) {
    errors.push('PARKIO_SMOKE_REQUEST_DELAY_MS must be a non-negative number');
  }

  return {
    ok: errors.length === 0,
    errors,
    profile,
    baseUrl: String(baseUrl).replace(/\/$/, ''),
    apiBase: String(baseUrl).replace(/\/$/, '') + '/api/v1',
    emailA,
    passwordA,
    emailB,
    passwordB,
    hasUserB,
    lat,
    lng,
    runId,
    evidenceDir,
    requestDelayMs,
    observeEvents: env.PARKIO_SMOKE_OBSERVE_EVENTS === '1',
    pageSize: Math.min(Math.max(Number(env.PARKIO_SMOKE_HISTORY_PAGE_SIZE || 2), 1), 20),
  };
}

module.exports = {
  CLIENT_HEADER,
  DEFAULT_SMOKE_LAT,
  DEFAULT_SMOKE_LNG,
  redactSecrets,
  shortId,
  validateCoordinates,
  resolveSmokeConfig,
};