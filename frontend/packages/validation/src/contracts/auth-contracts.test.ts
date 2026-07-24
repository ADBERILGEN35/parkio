import { describe, expect, it } from 'vitest';
import { apiErrorSchema } from '../api-error';
import {
  apiErrorFixture,
  nativeAuthResponseFixture,
  pendingVerificationAuthResponseFixture,
  webAuthResponseFixture,
} from './contract-fixtures';
import {
  authResponseSchema,
  loginRequestSchema,
  logoutRequestSchema,
  mobileTokenRequestSchema,
  refreshTokenRequestSchema,
  registerRequestSchema,
} from './auth';

describe('authentication request contracts', () => {
  it('rejects unknown request properties', () => {
    expect(
      loginRequestSchema.safeParse({
        email: 'driver@parkio.dev',
        password: 'password',
        rememberMe: true,
      }).success,
    ).toBe(false);
    expect(
      registerRequestSchema.safeParse({
        email: 'driver@parkio.dev',
        password: 'ValidPassword123',
        displayName: 'Client-controlled profile field',
      }).success,
    ).toBe(false);
    expect(
      mobileTokenRequestSchema.safeParse({ refreshToken: 'token', accessToken: 'unexpected' }).success,
    ).toBe(false);
  });

  it('keeps the native credential body explicit and non-empty', () => {
    expect(mobileTokenRequestSchema.safeParse({ refreshToken: 'native-refresh-token' }).success).toBe(
      true,
    );
    expect(mobileTokenRequestSchema.safeParse({ refreshToken: '' }).success).toBe(false);
    expect(refreshTokenRequestSchema.safeParse(undefined).success).toBe(true);
    expect(logoutRequestSchema.safeParse(undefined).success).toBe(true);
  });
});

describe('authentication and error responses', () => {
  it('accepts web, native, and pending-verification auth fixtures', () => {
    expect(authResponseSchema.parse(webAuthResponseFixture)).toEqual(webAuthResponseFixture);
    expect(authResponseSchema.parse(nativeAuthResponseFixture)).toEqual(nativeAuthResponseFixture);
    expect(authResponseSchema.parse(pendingVerificationAuthResponseFixture)).toEqual(
      pendingVerificationAuthResponseFixture,
    );
  });

  it('strips additive response fields recursively', () => {
    const parsed = authResponseSchema.parse({
      ...webAuthResponseFixture,
      futureAuthField: 'ignored',
      user: { ...webAuthResponseFixture.user, futureUserField: 'ignored' },
    });

    expect(parsed).not.toHaveProperty('futureAuthField');
    expect(parsed.user).not.toHaveProperty('futureUserField');
  });

  it('fails closed for unknown auth enum values', () => {
    expect(
      authResponseSchema.safeParse({
        ...webAuthResponseFixture,
        user: { ...webAuthResponseFixture.user, status: 'DEACTIVATED' },
      }).success,
    ).toBe(false);
  });

  it('validates the standard ApiError while tolerating additive fields', () => {
    const parsed = apiErrorSchema.parse({
      ...apiErrorFixture,
      futureErrorField: 'ignored',
      fieldErrors: [{ ...apiErrorFixture.fieldErrors[0], futureFieldErrorField: 'ignored' }],
    });

    expect(parsed).not.toHaveProperty('futureErrorField');
    expect(parsed.fieldErrors?.[0]).not.toHaveProperty('futureFieldErrorField');
    expect(apiErrorSchema.safeParse({ ...apiErrorFixture, timestamp: 'not-a-date' }).success).toBe(
      false,
    );
  });
});
