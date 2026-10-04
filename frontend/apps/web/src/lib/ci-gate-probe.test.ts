import { describe, expect, it } from 'vitest';

// DO NOT MERGE: CI gate negative probe (a deliberate assertion failure).
describe('CI gate probe', () => {
  it('fails on purpose', () => {
    expect(1).toBe(2);
  });
});
