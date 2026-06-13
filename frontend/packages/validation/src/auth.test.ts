import { describe, expect, it } from 'vitest';
import { loginSchema, registerSchema } from './auth';

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
