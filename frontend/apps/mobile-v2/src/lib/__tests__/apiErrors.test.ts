import { ParkioApiError } from '@parkio/api-client';
import { tr } from '@/i18n/translations';
import { interpolate } from '@/i18n/LocaleProvider';
import type { TranslationKey } from '@/i18n/translations';
import { describeApiError } from '../apiErrors';

const t = (key: TranslationKey, params?: Record<string, string | number>) =>
  interpolate(tr[key], params);

function apiError(status: number, code: string, message = 'backend message', traceId = 'trc_123') {
  return new ParkioApiError(status, { code, message, traceId, timestamp: new Date().toISOString() });
}

describe('describeApiError', () => {
  it('maps known backend codes to localized copy', () => {
    expect(describeApiError(apiError(401, 'INVALID_CREDENTIALS'), t).message).toBe(
      tr['auth.login.invalid'],
    );
    expect(describeApiError(apiError(409, 'ALREADY_VERIFIED'), t).message).toBe(
      tr['spot.verify.duplicate'],
    );
    expect(describeApiError(apiError(409, 'OWNER_CANNOT_CLAIM'), t).message).toBe(
      tr['spot.claim.own'],
    );
    expect(describeApiError(apiError(409, 'MEDIA_NOT_READY'), t).message).toBe(
      tr['share.mediaNotReady'],
    );
    expect(describeApiError(apiError(422, 'ILLEGAL_SPOT_REJECTED'), t).message).toBe(
      tr['share.illegalRejected'],
    );
  });

  it('keeps the trace id for support', () => {
    const described = describeApiError(apiError(500, 'SOMETHING_ELSE'), t);
    expect(described.traceId).toBe('trc_123');
  });

  it('treats an envelope-less UNKNOWN_ERROR as a network failure', () => {
    const described = describeApiError(apiError(500, 'UNKNOWN_ERROR', 'x', ''), t);
    expect(described.message).toBe(tr['common.error.network']);
  });

  it('falls back to the generic line for unknown errors', () => {
    expect(describeApiError(new Error('boom'), t).message).toBe(tr['common.error.generic']);
  });

  it('surfaces backend 4xx messages verbatim when the code is unknown', () => {
    const described = describeApiError(apiError(400, 'SOME_NEW_CODE', 'Çok özel bir mesaj'), t);
    expect(described.message).toBe('Çok özel bir mesaj');
  });
});
