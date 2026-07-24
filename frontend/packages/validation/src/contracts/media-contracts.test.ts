import { describe, expect, it } from 'vitest';
import {
  aiValidationResultFixture,
  mediaAccessUrlFixture,
  mediaMetadataFixture,
} from './contract-fixtures';
import {
  aiValidationResultResponseSchema,
  claimedRegionResponseSchema,
  mediaAccessUrlResponseSchema,
  mediaMetadataResponseSchema,
} from './media';

describe('media response contracts', () => {
  it('accepts the frozen media response fixtures', () => {
    expect(mediaAccessUrlResponseSchema.parse(mediaAccessUrlFixture)).toEqual(mediaAccessUrlFixture);
    expect(mediaMetadataResponseSchema.parse(mediaMetadataFixture)).toEqual(mediaMetadataFixture);
    expect(aiValidationResultResponseSchema.parse(aiValidationResultFixture)).toEqual(
      aiValidationResultFixture,
    );
  });

  it('preserves additive response compatibility recursively', () => {
    const parsed = aiValidationResultResponseSchema.parse({
      ...aiValidationResultFixture,
      futureResultField: 'ignored',
      findings: [
        {
          ...aiValidationResultFixture.findings[0],
          futureFindingField: 'ignored',
        },
      ],
    });

    expect(parsed).not.toHaveProperty('futureResultField');
    expect(parsed.findings[0]).not.toHaveProperty('futureFindingField');
  });

  it('rejects unknown closed-enum values', () => {
    expect(
      mediaMetadataResponseSchema.safeParse({ ...mediaMetadataFixture, status: 'QUARANTINED' }).success,
    ).toBe(false);
    expect(
      aiValidationResultResponseSchema.safeParse({
        ...aiValidationResultFixture,
        status: 'UNKNOWN',
      }).success,
    ).toBe(false);
  });
});

describe('claimed-region primitive contract', () => {
  it('rejects impossible normalized geometry', () => {
    expect(
      claimedRegionResponseSchema.safeParse({ x: 0.8, y: 0.1, width: 0.5, height: 0.5 }).success,
    ).toBe(false);
    expect(
      claimedRegionResponseSchema.safeParse({ x: 0.1, y: 0.8, width: 0.5, height: 0.5 }).success,
    ).toBe(false);
    expect(
      claimedRegionResponseSchema.safeParse({ x: 0.1, y: 0.1, width: 0.1, height: 0.1 }).success,
    ).toBe(false);
  });
});
