/**
 * Server-side refresh-session + epoch probe for the CSRF gateway HTTPS lab.
 * Reads Postgres via docker exec — not Playwright headers.
 */
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

export function hashRefreshToken(raw) {
  return createHash('sha256').update(String(raw), 'utf8').digest('hex');
}

function sqlLiteral(value) {
  return `'${String(value).replace(/'/g, "''")}'`;
}

/**
 * @returns {{
 *   userId: string,
 *   sessionEpoch: number,
 *   tokenId: string|null,
 *   revoked: boolean|null,
 *   reusedDetected: boolean|null,
 *   revokedReason: string|null,
 *   tokenVersion: number|null,
 *   activeRefreshCount: number,
 *   tokenHashPrefix: string
 * }}
 */
export function readSessionState({ containerId, email, rawRefreshCookie }) {
  if (!containerId) throw new Error('containerId required');
  if (!email) throw new Error('email required');
  if (!rawRefreshCookie) throw new Error('rawRefreshCookie required');

  const tokenHash = hashRefreshToken(rawRefreshCookie);
  const sql = `
SELECT u.id::text,
       u.session_epoch::text,
       COALESCE(t.id::text, ''),
       COALESCE(t.revoked::text, ''),
       COALESCE(t.reused_detected::text, ''),
       COALESCE(t.revoked_reason::text, ''),
       COALESCE(t.version::text, ''),
       (SELECT count(*)::text FROM refresh_tokens r
         WHERE r.user_id = u.id AND r.revoked = false)
  FROM auth_users u
  LEFT JOIN refresh_tokens t ON t.token_hash = ${sqlLiteral(tokenHash)}
 WHERE lower(u.email) = lower(${sqlLiteral(email)});
`.trim();

  const out = execFileSync(
    'docker',
    ['exec', containerId, 'psql', '-U', 'csrf', '-d', 'parkio_auth', '-t', '-A', '-F', '|', '-c', sql],
    { encoding: 'utf8' },
  ).trim();
  if (!out) {
    throw new Error(`session probe returned empty for ${email}`);
  }
  const [userId, epoch, tokenId, revoked, reused, reason, version, active] = out.split('|');
  return {
    userId,
    sessionEpoch: Number(epoch),
    tokenId: tokenId || null,
    revoked: revoked === '' ? null : revoked === 't' || revoked === 'true',
    reusedDetected: reused === '' ? null : reused === 't' || reused === 'true',
    revokedReason: reason || null,
    tokenVersion: version === '' ? null : Number(version),
    activeRefreshCount: Number(active),
    tokenHashPrefix: tokenHash.slice(0, 12),
  };
}

export function assertSessionUnchanged(before, after, label) {
  const fields = [
    'userId',
    'sessionEpoch',
    'tokenId',
    'revoked',
    'reusedDetected',
    'revokedReason',
    'tokenVersion',
    'activeRefreshCount',
  ];
  const diffs = fields.filter((f) => before[f] !== after[f]);
  if (diffs.length > 0) {
    const err = new Error(
      `SECURITY DEFECT: refresh-session/epoch changed after ${label}: ${diffs.join(', ')} ` +
        `before=${JSON.stringify(before)} after=${JSON.stringify(after)}`,
    );
    err.code = 'CSRF_SESSION_MUTATION';
    throw err;
  }
}

export function readJsonl(path) {
  try {
    return readFileSync(path, 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((line) => JSON.parse(line));
  } catch {
    return [];
  }
}

/**
 * Attribute which layer handled a browser attempt using edge + auth capture logs.
 * Browser status 0 alone is never treated as proof of auth Origin rejection.
 */
export function attributeLayers({
  edgeRows,
  authRows,
  origin,
  pathIncludes,
  sinceTs,
  browserStatus,
}) {
  const after = (row) => !sinceTs || !row.ts || row.ts >= sinceTs;
  const edge = edgeRows.filter(
    (r) =>
      after(r) &&
      r.path &&
      pathIncludes.some((p) => r.path.includes(p)) &&
      r.origin === origin,
  );
  const auth = authRows.filter(
    (r) =>
      after(r) &&
      r.path &&
      pathIncludes.some((p) => r.path.includes(p)) &&
      r.origin === origin,
  );
  const edgeOptions = edge.filter((r) => r.method === 'OPTIONS');
  const edgePosts = edge.filter((r) => r.method === 'POST');
  const authPosts = auth.filter((r) => r.method === 'POST' || !r.method);

  let rejectionLayer;
  if (authPosts.length > 0) {
    rejectionLayer = 'auth-received';
  } else if (edgePosts.length > 0) {
    rejectionLayer = 'gateway-post-no-auth-forward';
  } else if (edgeOptions.length > 0 && edgePosts.length === 0) {
    rejectionLayer = 'gateway-preflight-blocked-post';
  } else if (browserStatus === 0) {
    rejectionLayer = 'browser-blocked-or-omitted-before-edge';
  } else {
    rejectionLayer = `http-${browserStatus}-unattributed`;
  }

  return {
    reachedGateway: edgeOptions.length > 0 || edgePosts.length > 0,
    preflightSeen: edgeOptions.length > 0,
    preflightBlockedPost: edgeOptions.length > 0 && edgePosts.length === 0,
    reachedAuth: authPosts.length > 0,
    edgeOptions: edgeOptions.length,
    edgePosts: edgePosts.length,
    authPosts: authPosts.length,
    rejectionLayer,
    authStatuses: authPosts.map((r) => r.status),
  };
}
