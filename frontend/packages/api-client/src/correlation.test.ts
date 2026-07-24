import { describe, expect, it } from 'vitest';
import { createCorrelationId, createRequestId } from './correlation';

const uuidV4Pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

describe('SDK identifiers', () => {
  it('creates platform-neutral UUID request and correlation identifiers', () => {
    expect(createRequestId()).toMatch(uuidV4Pattern);
    expect(createCorrelationId()).toMatch(uuidV4Pattern);
  });

  it('does not reuse identifiers between logical requests or attempts', () => {
    const identifiers = new Set([
      createRequestId(),
      createRequestId(),
      createCorrelationId(),
      createCorrelationId(),
    ]);

    expect(identifiers.size).toBe(4);
  });
});
