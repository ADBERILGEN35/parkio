import { ParkioApiError, UnauthorizedError } from '@parkio/api-client';
import { toUserMessage } from '../errors';

function apiError(status: number, overrides: Record<string, unknown> = {}) {
  return new ParkioApiError(status, {
    code: 'X',
    message: 'raw message',
    traceId: 't',
    timestamp: '2026-01-01T00:00:00Z',
    ...overrides,
  });
}

/** The shared client throws an UnauthorizedError for every 401. */
function unauthorized(code: string) {
  return new UnauthorizedError({
    code,
    message: `${code} occurred`,
    traceId: 't',
    timestamp: '2026-01-01T00:00:00Z',
  });
}

describe('toUserMessage', () => {
  it('maps 401 to a session-expired message', () => {
    expect(toUserMessage(apiError(401))).toMatch(/session has expired/i);
  });

  it('maps a 401 INVALID_CREDENTIALS to a wrong-password message, not an expired session', () => {
    // A wrong current password on change-password: the access token is still valid (#604).
    const message = toUserMessage(unauthorized('INVALID_CREDENTIALS'));
    expect(message).toBe('Incorrect email or password.');
    expect(message).not.toMatch(/session/i);
  });

  it.each(['MISSING_TOKEN', 'INVALID_TOKEN', 'TOKEN_REVOKED'])(
    'keeps the session-expired message for a token 401 %s',
    (code) => {
      expect(toUserMessage(unauthorized(code))).toBe(
        'Your session has expired. Please sign in again.',
      );
    },
  );

  it('maps 5xx to a friendly server message (no raw text)', () => {
    const msg = toUserMessage(apiError(503));
    expect(msg).toMatch(/servers/i);
    expect(msg).not.toContain('raw message');
  });

  it('maps 429 to a rate-limit message', () => {
    expect(toUserMessage(apiError(429))).toMatch(/too many/i);
  });

  it('maps 413 to a photo-too-large message', () => {
    expect(toUserMessage(apiError(413))).toMatch(/too large/i);
  });

  it('maps 415 to an unsupported-format message', () => {
    expect(toUserMessage(apiError(415))).toMatch(/format/i);
  });

  it('surfaces the first field error for 400s', () => {
    const msg = toUserMessage(apiError(400, { fieldErrors: { email: ['Email already in use'] } }));
    expect(msg).toBe('Email already in use');
  });

  it('detects network errors', () => {
    expect(toUserMessage({ code: 'ERR_NETWORK', message: 'Network Error' })).toMatch(/offline/i);
  });

  it('falls back to a generic message for unknown values', () => {
    expect(toUserMessage(null)).toMatch(/something went wrong/i);
  });
});
