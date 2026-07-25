import { PARKING_STATUSES } from '@parkio/types';
import { describe, expect, it } from 'vitest';
import { isUsableSpot, presentSpot } from './spotPresentation';

describe('presentSpot', () => {
  it('maps VERIFIED to available + high confidence + success tone', () => {
    const p = presentSpot({ status: 'VERIFIED', legalStatus: 'LEGAL' });
    expect(p.availability).toBe('available');
    expect(p.confidence).toBe('high');
    expect(p.tone).toBe('success');
    expect(p.legalLabel).toBe('Legal parking');
  });

  it('maps SUSPICIOUS to unverified + low confidence + warning tone', () => {
    const p = presentSpot({ status: 'SUSPICIOUS', legalStatus: 'UNCERTAIN' });
    expect(p.availability).toBe('unverified');
    expect(p.confidence).toBe('low');
    expect(p.tone).toBe('warning');
  });

  it('maps FILLED to filled + no confidence + danger tone', () => {
    const p = presentSpot({ status: 'FILLED', legalStatus: 'LEGAL' });
    expect(p.availability).toBe('filled');
    expect(p.confidence).toBe('none');
    expect(p.tone).toBe('danger');
  });

  it('maps EXPIRED to muted', () => {
    expect(presentSpot({ status: 'EXPIRED', legalStatus: 'LEGAL' }).tone).toBe('muted');
  });
});

describe('isUsableSpot', () => {
  it('is true for available/unverified, false for pending/filled/expired/rejected/failed', () => {
    expect(isUsableSpot({ status: 'ACTIVE' })).toBe(true);
    expect(isUsableSpot({ status: 'VERIFIED' })).toBe(true);
    expect(isUsableSpot({ status: 'SUSPICIOUS' })).toBe(true);
    expect(isUsableSpot({ status: 'PENDING_VALIDATION' })).toBe(false);
    expect(isUsableSpot({ status: 'PENDING_REVIEW' })).toBe(false);
    expect(isUsableSpot({ status: 'FILLED' })).toBe(false);
    expect(isUsableSpot({ status: 'EXPIRED' })).toBe(false);
    expect(isUsableSpot({ status: 'REJECTED' })).toBe(false);
    expect(isUsableSpot({ status: 'REVIEW_FAILED' })).toBe(false);
  });
});

describe('presentSpot REVIEW_FAILED', () => {
  it('is a distinct terminal state, never conflated with pending or rejected', () => {
    const failed = presentSpot({ status: 'REVIEW_FAILED', legalStatus: 'LEGAL' });

    expect(failed.availability).toBe('review_failed');
    expect(failed.statusLabel).toBe('Review failed');
    expect(failed.availabilityLabel).toBe('Review could not be completed');
    expect(failed.confidence).toBe('none');
    // Distinguishable from every other lifecycle state the UI can show.
    const others = (
      [
        'PENDING_VALIDATION',
        'PENDING_REVIEW',
        'ACTIVE',
        'VERIFIED',
        'SUSPICIOUS',
        'FILLED',
        'EXPIRED',
        'REJECTED',
      ] as const
    ).map((status) => presentSpot({ status, legalStatus: 'LEGAL' }).availability);
    expect(others).not.toContain('review_failed');
  });
});

describe('lifecycle presentation is total', () => {
  it('gives every backend status a distinct, non-empty label', () => {
    const labels = PARKING_STATUSES.map(
      (status) => presentSpot({ status, legalStatus: 'LEGAL' }).statusLabel,
    );
    expect(labels.every((label) => label.length > 0)).toBe(true);
    // No two lifecycle states may render identically — the user must always be able to
    // tell pending from approved, rejected, timed-out and expired.
    expect(new Set(labels).size).toBe(PARKING_STATUSES.length);
  });
});

describe('presentSpot pending statuses', () => {
  it('maps PENDING_VALIDATION and PENDING_REVIEW as unverified + low confidence', () => {
    const validating = presentSpot({ status: 'PENDING_VALIDATION', legalStatus: 'LEGAL' });
    expect(validating.availability).toBe('unverified');
    expect(validating.statusLabel).toBe('Validating');
    expect(validating.confidence).toBe('low');

    const review = presentSpot({ status: 'PENDING_REVIEW', legalStatus: 'UNCERTAIN' });
    expect(review.availability).toBe('unverified');
    expect(review.statusLabel).toBe('Under review');
  });
});
