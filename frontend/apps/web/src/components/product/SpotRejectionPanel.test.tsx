import type { SpotRejection } from '@parkio/types';
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/utils';
import { SpotRejectionPanel } from './SpotRejectionPanel';

const LEGACY: SpotRejection = {
  code: 'LEGACY_POLICY_RESET',
  message: 'Server snapshot that must not override i18n.',
  source: 'SYSTEM_MIGRATION',
  rejectedAt: '2026-07-29T12:00:00Z',
  rejectedBy: null,
  policyVersion: '2026-07-photo-policy-v3-recall',
};

describe('SpotRejectionPanel compatibility wrapper', () => {
  it('delegates to ModerationDecisionCard', () => {
    renderWithProviders(<SpotRejectionPanel rejection={LEGACY} status="REJECTED" />);
    expect(screen.getByTestId('moderation-decision-card')).toHaveAttribute(
      'data-rejection-variant',
      'SYSTEM_MIGRATION',
    );
  });

  it('hides when rejection is absent', () => {
    renderWithProviders(<SpotRejectionPanel rejection={null} />);
    expect(screen.queryByTestId('moderation-decision-card')).not.toBeInTheDocument();
  });
});
