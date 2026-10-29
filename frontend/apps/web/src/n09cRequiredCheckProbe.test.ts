import { describe, expect, it } from 'vitest';

// Synthetic #418 probe on a throwaway PR that is never merged: a red Frontend CI job must block merge.
describe('n09c required-check probe', () => {
  it('fails on purpose', () => {
    expect('red').toBe('green');
  });
});
