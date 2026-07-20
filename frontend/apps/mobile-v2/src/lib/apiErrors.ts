import { isParkioApiError } from '@parkio/api-client';
import type { Translator } from '@/i18n/LocaleProvider';
import type { TranslationKey } from '@/i18n/translations';

/**
 * API error → human copy. Known backend codes map to specific strings; anything
 * else falls back to a generic line plus the trace id (the support handle).
 */
const CODE_TO_KEY: Record<string, TranslationKey> = {
  INVALID_CREDENTIALS: 'auth.login.invalid',
  ACCOUNT_NOT_VERIFIED: 'auth.login.notVerified',
  EMAIL_ALREADY_EXISTS: 'auth.register.emailTaken',
  EMAIL_ALREADY_REGISTERED: 'auth.register.emailTaken',
  INVALID_TOKEN: 'auth.reset.expired',
  TOKEN_EXPIRED: 'auth.reset.expired',
  INVALID_CURRENT_PASSWORD: 'profile.password.wrongCurrent',
  CURRENT_PASSWORD_MISMATCH: 'profile.password.wrongCurrent',
  ALREADY_VERIFIED: 'spot.verify.duplicate',
  OWNER_CANNOT_VERIFY: 'spot.verify.own',
  OWNER_CANNOT_CLAIM: 'spot.claim.own',
  SPOT_EXPIRED: 'spot.expired.body',
  SPOT_NOT_VERIFIABLE: 'spot.expired.body',
  SPOT_NOT_CLAIMABLE: 'spot.filled.body',
  DUPLICATE_REPORT: 'spot.report.duplicate',
  DUPLICATE_APPEAL: 'reports.appeal.duplicate',
  CASE_NOT_RESOLVED: 'reports.appeal.notResolved',
  MEDIA_NOT_READY: 'share.mediaNotReady',
  MEDIA_NOT_FOUND: 'share.mediaNotReady',
  ILLEGAL_SPOT_REJECTED: 'share.illegalRejected',
  SPOT_NOT_FOUND: 'spot.notFound',
};

export interface FriendlyApiError {
  message: string;
  traceId: string | null;
  code: string | null;
}

export function describeApiError(error: unknown, t: Translator): FriendlyApiError {
  if (isParkioApiError(error)) {
    const key = CODE_TO_KEY[error.code];
    if (key) {
      return { message: t(key), traceId: error.traceId || null, code: error.code };
    }
    // Network-shaped failures surface as UNKNOWN_ERROR with no backend envelope
    // (axios error without a response → status 500 fallback, empty traceId).
    if (error.code === 'UNKNOWN_ERROR' && !error.traceId) {
      return { message: t('common.error.network'), traceId: null, code: error.code };
    }
    const backendMessage = typeof error.message === 'string' ? error.message.trim() : '';
    return {
      // Backend messages are already user-safe per the error envelope contract,
      // but prefer our localized generic line unless the code was recognized.
      message: backendMessage.length > 0 && error.status < 500 ? backendMessage : t('common.error.generic'),
      traceId: error.traceId || null,
      code: error.code,
    };
  }
  if (error instanceof Error && error.message === 'Network Error') {
    return { message: t('common.error.network'), traceId: null, code: null };
  }
  return { message: t('common.error.generic'), traceId: null, code: null };
}

export function apiErrorCode(error: unknown): string | null {
  return isParkioApiError(error) ? error.code : null;
}
