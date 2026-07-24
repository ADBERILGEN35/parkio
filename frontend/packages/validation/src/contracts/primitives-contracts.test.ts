import { describe, expect, it } from 'vitest';
import { localDateSchema } from './primitives';

describe('LocalDate primitive contract', () => {
  it('accepts valid calendar dates including leap day', () => {
    expect(localDateSchema.safeParse('2024-02-29').success).toBe(true);
    expect(localDateSchema.safeParse('2026-07-22').success).toBe(true);
  });

  it('rejects impossible calendar dates', () => {
    expect(localDateSchema.safeParse('2026-02-29').success).toBe(false);
    expect(localDateSchema.safeParse('2026-02-31').success).toBe(false);
    expect(localDateSchema.safeParse('2026-13-01').success).toBe(false);
    expect(localDateSchema.safeParse('2026-00-10').success).toBe(false);
    expect(localDateSchema.safeParse('2026-04-31').success).toBe(false);
  });
});
