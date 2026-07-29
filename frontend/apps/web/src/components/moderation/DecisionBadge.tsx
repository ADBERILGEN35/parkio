import { SoftBadge, type BadgeTone } from '@parkio/ui';
import { useTranslation } from 'react-i18next';
import type { RejectionPresentationVariant } from '@/lib/getRejectionPresentation';

export type DecisionBadgeKind =
  | RejectionPresentationVariant
  | 'ACCEPTED'
  | 'REJECTED'
  | 'REVIEW'
  | 'SYSTEM_MIGRATION_STATUS';

const KIND_ICON: Record<DecisionBadgeKind, string> = {
  AI_POLICY: 'smart_toy',
  MODERATOR: 'person',
  SYSTEM_MIGRATION: 'settings_suggest',
  UNKNOWN: 'help',
  ACCEPTED: 'check_circle',
  REJECTED: 'cancel',
  REVIEW: 'rate_review',
  SYSTEM_MIGRATION_STATUS: 'settings_suggest',
};

const KIND_TONE: Record<DecisionBadgeKind, BadgeTone> = {
  AI_POLICY: 'danger',
  MODERATOR: 'warning',
  SYSTEM_MIGRATION: 'neutral',
  UNKNOWN: 'neutral',
  ACCEPTED: 'success',
  REJECTED: 'danger',
  REVIEW: 'warning',
  SYSTEM_MIGRATION_STATUS: 'neutral',
};

/**
 * Reusable decision/status badge for moderation, spot detail, and admin surfaces.
 * Uses Material Symbols (not emoji) for contrast and screen-reader labels.
 */
export function DecisionBadge({
  kind,
  label,
}: {
  kind: DecisionBadgeKind;
  /** Optional override; defaults to i18n `decision.badge.*`. */
  label?: string;
}) {
  const { t } = useTranslation('parking');
  const text = label ?? t(`decision.badge.${kind}`);
  return (
    <SoftBadge
      tone={KIND_TONE[kind]}
      icon={KIND_ICON[kind]}
      data-testid={`decision-badge-${kind}`}
      aria-label={text}
    >
      {text}
    </SoftBadge>
  );
}
