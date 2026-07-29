import type { SpotRejection } from '@parkio/types';
import { fireEvent, screen } from '@testing-library/react-native';
import { DecisionBadge, DecisionCard } from '@/features/moderation/decision';
import { renderWithProviders } from '@/test/renderWithProviders';

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

describe('DecisionCard', () => {
  it('hides when rejection is absent', () => {
    renderWithProviders(<DecisionCard rejection={null} />);
    expect(screen.queryByTestId('moderation-decision-card')).toBeNull();
  });

  it('renders AI decision with localized reason and hides confidence when absent', () => {
    renderWithProviders(<DecisionCard rejection={AI} status="REJECTED" />);

    const card = screen.getByTestId('moderation-decision-card');
    expect(card.props.accessibilityHint).toBe('AI_POLICY');
    expect(screen.getByText('AI kararı')).toBeTruthy();
    expect(screen.getByTestId('spot-rejection-message')).toHaveTextContent(
      /park yeri veya yol bağlamıyla/i,
    );
    expect(screen.queryByTestId('decision-confidence')).toBeNull();
    expect(screen.getByTestId('decision-metadata')).toBeTruthy();
  });

  it('shows confidence as supporting information when provided', () => {
    renderWithProviders(
      <DecisionCard rejection={AI} status="REJECTED" confidenceScore={0.92} />,
    );

    expect(screen.getByTestId('decision-confidence')).toHaveTextContent(/92%/);
    expect(screen.getByTestId('decision-confidence')).toHaveTextContent(
      /destekleyici bilgidir/i,
    );
  });

  it('renders moderator decision with separate note section', () => {
    renderWithProviders(<DecisionCard rejection={MODERATOR} status="REJECTED" />);

    expect(screen.getByTestId('moderation-decision-card').props.accessibilityHint).toBe(
      'MODERATOR',
    );
    expect(screen.getByText('Moderatör kararı')).toBeTruthy();
    expect(screen.getByTestId('moderator-note')).toHaveTextContent(/User uploaded a screenshot/);
    expect(screen.queryByTestId('decision-confidence')).toBeNull();
  });

  it('renders migration as non-AI system policy close', () => {
    renderWithProviders(<DecisionCard rejection={MIGRATION} status="REJECTED" />);

    expect(screen.getByTestId('moderation-decision-card').props.accessibilityHint).toBe(
      'SYSTEM_MIGRATION',
    );
    expect(screen.getByText('Sistem politika geçişi')).toBeTruthy();
    expect(screen.getByTestId('decision-subtitle')).toHaveTextContent(
      /Görüntü kalitesi nedeniyle reddedilmedi/i,
    );
    expect(screen.queryByText('AI kararı')).toBeNull();
  });

  it('falls back safely for unknown codes', () => {
    renderWithProviders(
      <DecisionCard
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

  it('keeps technical details collapsed until toggled', () => {
    renderWithProviders(
      <DecisionCard
        rejection={AI}
        technical={{ provider: 'gemini', providerReason: 'UNRELATED_SUBJECT' }}
      />,
    );

    expect(screen.getByTestId('technical-details')).toBeTruthy();
    expect(screen.queryByText('gemini')).toBeNull();

    fireEvent.press(screen.getByLabelText('Teknik ayrıntılar'));
    expect(screen.getByText('gemini')).toBeTruthy();
    expect(screen.getByText('UNRELATED_SUBJECT')).toBeTruthy();
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

    expect(screen.getByTestId('decision-badge-AI_POLICY').props.accessibilityLabel).toBe('AI');
    expect(screen.getByTestId('decision-badge-MODERATOR').props.accessibilityLabel).toBe(
      'Moderatör',
    );
    expect(screen.getByTestId('decision-badge-SYSTEM_MIGRATION').props.accessibilityLabel).toBe(
      'Migrasyon',
    );
  });
});
