import { ParkioApiError } from '@parkio/api-client';
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

describe('toUserMessage', () => {
  it('maps 401 to a session-expired message', () => {
    expect(toUserMessage(apiError(401))).toMatch(/session has expired/i);
  });

  it('maps 5xx to a friendly server message (no raw text)', () => {
    const msg = toUserMessage(apiError(503));
    expect(msg).toMatch(/servers/i);
    expect(msg).not.toContain('raw message');
  });

  it('maps 429 to a rate-limit message', () => {
    expect(toUserMessage(apiError(429))).toMatch(/too many/i);
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
