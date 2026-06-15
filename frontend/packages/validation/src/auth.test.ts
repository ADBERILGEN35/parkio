import { describe, expect, it } from 'vitest';
import { loginSchema, registerProfileSchema, registerSchema } from './auth';

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
  it('accepts a valid email and 8+ char password', () => {
    expect(registerSchema.safeParse({ email: 'a@b.com', password: '12345678' }).success).toBe(true);
  });

  it('rejects passwords shorter than 8 characters', () => {
    expect(registerSchema.safeParse({ email: 'a@b.com', password: '1234567' }).success).toBe(false);
  });

  it('rejects passwords longer than 100 characters', () => {
    expect(
      registerSchema.safeParse({ email: 'a@b.com', password: 'p'.repeat(101) }).success,
    ).toBe(false);
  });

  it('rejects an invalid email', () => {
    expect(registerSchema.safeParse({ email: 'nope', password: '12345678' }).success).toBe(false);
  });
});

describe('registerProfileSchema', () => {
  const valid = {
    displayName: 'Ada Lovelace',
    email: 'a@b.com',
    phoneNumber: '',
    password: '12345678',
    confirmPassword: '12345678',
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
