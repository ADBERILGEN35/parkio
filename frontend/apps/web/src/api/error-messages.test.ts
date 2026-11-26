import {
  AccountNotActiveError,
  AccountNotVerifiedError,
  NetworkError,
  RateLimitError,
  ServerError,
  UnauthorizedError,
  UserStatusUnavailableError,
} from '@parkio/api-client';
import { describe, expect, it } from 'vitest';
import i18n from '@/i18n';
import { describeAuthError } from './error-messages';

function body(code: string) {
  return { code, message: 'Server message.', traceId: 'trace-test', timestamp: '2026-06-11T10:00:00Z' };
}

const SERVICE_UNAVAILABLE = {
  en: 'Service is temporarily unavailable. Please try again.',
  tr: 'Hizmet geçici olarak kullanılamıyor. Lütfen yeniden deneyin.',
};

const cases: [label: string, error: unknown, english: string, turkish: string][] = [
  [
    'a suspended account',
    new AccountNotActiveError(body('ACCOUNT_NOT_ACTIVE')),
    'Your account is suspended.',
    'Hesabınız askıya alındı.',
  ],
  [
    'an unverified account',
    new AccountNotVerifiedError(body('ACCOUNT_NOT_VERIFIED')),
    'Please verify your email before signing in.',
    'Giriş yapmadan önce e-postanızı doğrulayın.',
  ],
  [
    'a wrong password',
    new UnauthorizedError(body('INVALID_CREDENTIALS')),
    'Invalid email or password.',
    'E-posta veya şifre hatalı.',
  ],
  [
    'a rate limit',
    new RateLimitError(body('RATE_LIMITED')),
    'Too many attempts. Please wait a moment and try again.',
    'Çok fazla deneme yapıldı. Lütfen bir süre bekleyip yeniden deneyin.',
  ],
  [
    'an unavailable user status',
    new UserStatusUnavailableError(body('USER_STATUS_UNAVAILABLE')),
    SERVICE_UNAVAILABLE.en,
    SERVICE_UNAVAILABLE.tr,
  ],
  ['a server error', new ServerError(500, body('INTERNAL_ERROR')), SERVICE_UNAVAILABLE.en, SERVICE_UNAVAILABLE.tr],
];

describe('describeAuthError', () => {
  it.each(cases)('describes %s in English', (_label, error, english) => {
    expect(describeAuthError(error, 'Fallback.', i18n.getFixedT('en'))).toEqual({
      message: english,
      traceId: 'trace-test',
    });
  });

  it.each(cases)('describes %s in Turkish', (_label, error, _english, turkish) => {
    expect(describeAuthError(error, 'Fallback.', i18n.getFixedT('tr'))).toEqual({
      message: turkish,
      traceId: 'trace-test',
    });
  });

  it('returns the caller fallback for an error without an API response', () => {
    expect(describeAuthError(new NetworkError(), 'Fallback.', i18n.getFixedT('tr'))).toEqual({
      message: 'Fallback.',
    });
  });
});
