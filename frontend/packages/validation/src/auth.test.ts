import { describe, expect, it } from 'vitest';
import {
  loginSchema,
  PASSWORD_MAX_BYTES,
  registerProfileSchema,
  registerSchema,
  utf8ByteLength,
} from './auth';

describe('loginSchema', () => {
  it('accepts a valid email and password', () => {
    expect(loginSchema.safeParse({ email: 'a@b.com', password: 'x' }).success).toBe(true);
  });

  it('rejects an invalid email', () => {
    expect(loginSchema.safeParse({ email: 'not-an-email', password: 'x' }).success).toBe(false);
  });

  it('rejects an empty password', () => {
    expect(loginSchema.safeParse({ email: 'a@b.com', password: '' }).success).toBe(false);
  });
});

describe('registerSchema', () => {
  it('accepts a valid email and strong password', () => {
    expect(registerSchema.safeParse({ email: 'a@b.com', password: 'SaferPass123' }).success).toBe(
      true,
    );
  });

  it('rejects passwords shorter than 12 characters', () => {
    expect(registerSchema.safeParse({ email: 'a@b.com', password: 'Short123' }).success).toBe(
      false,
    );
  });

  it('rejects passwords missing required character classes', () => {
    expect(registerSchema.safeParse({ email: 'a@b.com', password: 'lowercase1234' }).success).toBe(
      false,
    );
    expect(registerSchema.safeParse({ email: 'a@b.com', password: 'UPPERCASE1234' }).success).toBe(
      false,
    );
    expect(registerSchema.safeParse({ email: 'a@b.com', password: 'NoDigitsHere' }).success).toBe(
      false,
    );
  });

  it('rejects common weak passwords', () => {
    expect(registerSchema.safeParse({ email: 'a@b.com', password: 'Password12345' }).success).toBe(
      false,
    );
  });

  it('rejects passwords longer than 100 characters', () => {
    expect(
      registerSchema.safeParse({ email: 'a@b.com', password: 'p'.repeat(101) }).success,
    ).toBe(false);
  });

  it('rejects an invalid email', () => {
    expect(registerSchema.safeParse({ email: 'nope', password: 'SaferPass123' }).success).toBe(
      false,
    );
  });
});

describe('registerProfileSchema', () => {
  const valid = {
    displayName: 'Ada Lovelace',
    email: 'a@b.com',
    phoneNumber: '',
    password: 'SaferPass123',
    confirmPassword: 'SaferPass123',
    termsAccepted: true,
  };

  it('accepts a fully valid form (phone optional)', () => {
    expect(registerProfileSchema.safeParse(valid).success).toBe(true);
  });

  it('accepts an optional phone number', () => {
    expect(
      registerProfileSchema.safeParse({ ...valid, phoneNumber: '5551234567' }).success,
    ).toBe(true);
  });

  it('requires a display name of at least 2 characters', () => {
    expect(registerProfileSchema.safeParse({ ...valid, displayName: 'A' }).success).toBe(false);
  });

  it('rejects mismatched passwords', () => {
    expect(
      registerProfileSchema.safeParse({ ...valid, confirmPassword: 'different1' }).success,
    ).toBe(false);
  });

  it('requires the terms to be accepted', () => {
    expect(registerProfileSchema.safeParse({ ...valid, termsAccepted: false }).success).toBe(false);
  });

  it('rejects a phone number longer than 32 characters', () => {
    expect(
      registerProfileSchema.safeParse({ ...valid, phoneNumber: '1'.repeat(33) }).success,
    ).toBe(false);
  });
});

describe('password byte limit (CL-F36)', () => {
  const seventyTwoBytes = `Aa1${'ş'.repeat(34)}x`;
  const seventyThreeBytes = `Aa1${'ş'.repeat(35)}`;

  it('counts UTF-8 bytes per code point', () => {
    expect(utf8ByteLength('abc')).toBe(3);
    expect(utf8ByteLength('ş')).toBe(2);
    expect(utf8ByteLength('€')).toBe(3);
    expect(utf8ByteLength('😀')).toBe(4);
    expect(utf8ByteLength(seventyTwoBytes)).toBe(PASSWORD_MAX_BYTES);
    expect(utf8ByteLength(seventyThreeBytes)).toBe(PASSWORD_MAX_BYTES + 1);
  });

  it('accepts a 72-byte password and rejects 73 bytes even under 100 characters', () => {
    expect(seventyThreeBytes.length).toBeLessThan(100);
    expect(registerSchema.safeParse({ email: 'a@b.com', password: seventyTwoBytes }).success).toBe(true);

    const result = registerSchema.safeParse({ email: 'a@b.com', password: seventyThreeBytes });

    expect(result.success).toBe(false);
    expect(result.error?.issues.map((issue) => issue.message)).toContain(
      'Password must be at most 72 bytes; letters such as ş or ğ count as two',
    );
  });
});
