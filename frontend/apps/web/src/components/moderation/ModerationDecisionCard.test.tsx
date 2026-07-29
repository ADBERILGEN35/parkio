import type { SpotRejection } from '@parkio/types';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/utils';
import { DecisionBadge } from './DecisionBadge';
import { ModerationDecisionCard } from './ModerationDecisionCard';

const AI: SpotRejection = {
  code: 'CLEARLY_UNRELATED_CONTENT',
  message: 'Server snapshot',
  source: 'AI_POLICY',
  rejectedAt: '2026-07-29T12:00:00Z',
  rejectedBy: null,
  policyVersion: '2026-07-photo-policy-v3-recall',
};

const MODERATOR: SpotRejection = {
  code: 'MANUAL_MODERATOR_REJECTION',
  message: 'User uploaded a screenshot.',
  source: 'MODERATOR',
  rejectedAt: '2026-07-29T12:00:00Z',
  rejectedBy: '0b8f6c3a-1111-0000-0000-000000000099',
  policyVersion: null,
  moderatorNote: 'User uploaded a screenshot.',
};

const MIGRATION: SpotRejection = {
  code: 'LEGACY_POLICY_RESET',
  message: 'Old snapshot',
  source: 'SYSTEM_MIGRATION',
  rejectedAt: '2026-07-29T12:00:00Z',
  rejectedBy: null,
  policyVersion: '2026-07-photo-policy-v3-recall',
};

describe('ModerationDecisionCard', () => {
  it('hides when rejection is absent', () => {
    renderWithProviders(<ModerationDecisionCard rejection={null} />);
    expect(screen.queryByTestId('moderation-decision-card')).not.toBeInTheDocument();
  });

  it('renders AI decision with localized reason and hides confidence when absent', () => {
    renderWithProviders(<ModerationDecisionCard rejection={AI} status="REJECTED" />);

    const card = screen.getByTestId('moderation-decision-card');
    expect(card).toHaveAttribute('data-rejection-variant', 'AI_POLICY');
    expect(screen.getByText(/AI decision/i)).toBeInTheDocument();
    expect(screen.getByTestId('spot-rejection-message')).toHaveTextContent(
      /does not appear related to a parking space/i,
    );
    expect(screen.queryByTestId('decision-confidence')).not.toBeInTheDocument();
    expect(screen.getByTestId('decision-metadata')).toBeInTheDocument();
  });

  it('shows confidence as supporting information when provided', () => {
    renderWithProviders(
      <ModerationDecisionCard rejection={AI} status="REJECTED" confidenceScore={0.92} />,
    );

    expect(screen.getByTestId('decision-confidence')).toHaveTextContent('92%');
    expect(screen.getByTestId('decision-confidence')).toHaveTextContent(/supporting information/i);
  });

  it('renders moderator decision with separate note section', () => {
    renderWithProviders(<ModerationDecisionCard rejection={MODERATOR} status="REJECTED" />);

    expect(screen.getByTestId('moderation-decision-card')).toHaveAttribute(
      'data-rejection-variant',
      'MODERATOR',
    );
    expect(screen.getByText(/moderator decision/i)).toBeInTheDocument();
    expect(screen.getByTestId('moderator-note')).toHaveTextContent('User uploaded a screenshot.');
    expect(screen.queryByTestId('decision-confidence')).not.toBeInTheDocument();
  });

  it('renders migration as non-AI system policy close', () => {
    renderWithProviders(<ModerationDecisionCard rejection={MIGRATION} status="REJECTED" />);

    expect(screen.getByTestId('moderation-decision-card')).toHaveAttribute(
      'data-rejection-variant',
      'SYSTEM_MIGRATION',
    );
    expect(screen.getByText(/system policy migration/i)).toBeInTheDocument();
    expect(screen.getByTestId('decision-subtitle')).toHaveTextContent(
      /NOT rejected because of image quality/i,
    );
    expect(screen.queryByText(/AI decision/i)).not.toBeInTheDocument();
  });

  it('falls back safely for unknown codes', () => {
    renderWithProviders(
      <ModerationDecisionCard
        rejection={{
          code: 'FUTURE_UNKNOWN_CODE',
          message: 'Compat fallback',
          source: 'AI_POLICY',
          rejectedAt: '2026-07-29T12:00:00Z',
          rejectedBy: null,
          policyVersion: null,
        }}
      />,
    );

    expect(screen.getByTestId('spot-rejection-message')).toHaveTextContent('Compat fallback');
  });

  it('keeps technical details collapsed until toggled', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ModerationDecisionCard
        rejection={AI}
        technical={{ provider: 'gemini', providerReason: 'UNRELATED_SUBJECT' }}
      />,
    );

    expect(screen.getByTestId('technical-details')).toBeInTheDocument();
    expect(screen.queryByText('gemini')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /technical details/i }));
    expect(screen.getByText('gemini')).toBeInTheDocument();
    expect(screen.getByText('UNRELATED_SUBJECT')).toBeInTheDocument();
  });
});

describe('DecisionBadge', () => {
  it('exposes accessible labels for decision kinds', () => {
    renderWithProviders(
      <>
        <DecisionBadge kind="AI_POLICY" />
        <DecisionBadge kind="MODERATOR" />
        <DecisionBadge kind="SYSTEM_MIGRATION" />
        <DecisionBadge kind="ACCEPTED" />
        <DecisionBadge kind="REVIEW" />
      </>,
    );

    expect(screen.getByTestId('decision-badge-AI_POLICY')).toHaveAttribute('aria-label', 'AI');
    expect(screen.getByTestId('decision-badge-MODERATOR')).toHaveAttribute(
      'aria-label',
      'Moderator',
    );
    expect(screen.getByTestId('decision-badge-SYSTEM_MIGRATION')).toHaveAttribute(
      'aria-label',
      'Migration',
    );
  });
});
