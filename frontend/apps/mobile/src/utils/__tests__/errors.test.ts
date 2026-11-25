import { ParkioApiError, UnauthorizedError } from '@parkio/api-client';
import { translate } from '@/i18n/translations';
import { ERROR_MESSAGES, toUserMessage } from '../errors';

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

/**
 * The copy is English source text, translated when shown: AppText (the global toast included)
 * passes it through `t`, which looks it up in the Turkish table (#643).
 */
describe('toUserMessage copy in each locale', () => {
  const cases: [label: string, error: unknown, english: string, turkish: string][] = [
    [
      'a wrong password (401 INVALID_CREDENTIALS)',
      unauthorized('INVALID_CREDENTIALS'),
      'Incorrect email or password.',
      'E-posta veya şifre hatalı.',
    ],
    [
      'an expired session (token 401)',
      unauthorized('INVALID_TOKEN'),
      'Your session has expired. Please sign in again.',
      'Oturumunuz sona erdi. Lütfen tekrar giriş yapın.',
    ],
    [
      'a 400 without field errors',
      apiError(400),
      'Please check the details and try again.',
      'Lütfen bilgileri kontrol edip tekrar deneyin.',
    ],
    ['a 403', apiError(403), 'You don’t have access to do that.', 'Bunu yapmak için yetkiniz yok.'],
    ['a 404', apiError(404), 'We couldn’t find what you were looking for.', 'Aradığınızı bulamadık.'],
    [
      'a 409 without a server message',
      apiError(409, { message: '' }),
      'That conflicts with something that already exists.',
      'Bu, zaten var olan bir kayıtla çakışıyor.',
    ],
    [
      'a 413',
      apiError(413),
      'That photo is too large to upload. Try a smaller one.',
      'Bu fotoğraf yüklemek için çok büyük. Daha küçük bir fotoğraf deneyin.',
    ],
    [
      'a 415',
      apiError(415),
      'That image format isn’t supported. Use a JPEG, PNG, or WebP photo.',
      'Bu görsel biçimi desteklenmiyor. JPEG, PNG veya WebP biçiminde bir fotoğraf kullanın.',
    ],
    [
      'a 429',
      apiError(429),
      'Too many attempts. Please wait a moment and try again.',
      'Çok fazla deneme yapıldı. Lütfen biraz bekleyip tekrar deneyin.',
    ],
    [
      'a 5xx',
      apiError(503),
      'Our servers are having a moment. Please try again shortly.',
      'Sunucularımızda geçici bir sorun var. Lütfen birazdan tekrar deneyin.',
    ],
    [
      'another 4xx without a server message',
      apiError(418, { message: '' }),
      'Something went wrong. Please try again.',
      'Bir şeyler ters gitti. Lütfen tekrar deneyin.',
    ],
    [
      'a network error',
      { code: 'ERR_NETWORK', message: 'Network Error' },
      'You appear to be offline. Check your connection and try again.',
      'Çevrimdışı görünüyorsunuz. Bağlantınızı kontrol edip tekrar deneyin.',
    ],
    [
      'an unknown value',
      null,
      'Something went wrong. Please try again.',
      'Bir şeyler ters gitti. Lütfen tekrar deneyin.',
    ],
  ];

  it.each(cases)('shows %s in English when the locale is en', (_label, error, english) => {
    expect(translate('en', toUserMessage(error))).toBe(english);
  });

  it.each(cases)('shows %s in Turkish when the locale is tr', (_label, error, _english, turkish) => {
    expect(translate('tr', toUserMessage(error))).toBe(turkish);
  });

  it('has a Turkish translation for every fixed message', () => {
    const untranslated = Object.values(ERROR_MESSAGES).filter(
      (message) => translate('tr', message) === message,
    );
    expect(untranslated).toEqual([]);
  });
});
