import type { SpotRejection } from '@parkio/types';
import { Icon, cn } from '@parkio/ui';
import { useTranslation } from 'react-i18next';
import { formatInstant } from '@/lib/format';
import {
  getRejectionPresentation,
  type RejectionPresentationVariant,
} from '@/lib/getRejectionPresentation';
import { DecisionBadge } from './DecisionBadge';
import { DecisionMetadata } from './DecisionMetadata';
import { ModeratorNote } from './ModeratorNote';
import { TechnicalDetailsAccordion, type TechnicalDetailRow } from './TechnicalDetailsAccordion';

export type DecisionTechnicalEvidence = {
  provider?: string | null;
  model?: string | null;
  providerReason?: string | null;
  providerConfidence?: number | null;
  evidenceId?: string | null;
  findings?: string[] | null;
};

const VARIANT_ICON: Record<RejectionPresentationVariant, string> = {
  AI_POLICY: 'smart_toy',
  MODERATOR: 'person',
  SYSTEM_MIGRATION: 'settings_suggest',
  UNKNOWN: 'help',
};

const VARIANT_SURFACE: Record<RejectionPresentationVariant, string> = {
  AI_POLICY: 'ring-error/25 bg-error/5',
  MODERATOR: 'ring-tertiary/30 bg-tertiary-container/10',
  SYSTEM_MIGRATION: 'ring-outline-variant/50 bg-surface-container-low',
  UNKNOWN: 'ring-outline-variant/40 bg-surface-container-low',
};

/**
 * Unified moderation decision card for AI, moderator, and system-migration outcomes.
 * Presentation copy comes from {@link getRejectionPresentation}; this component owns layout.
 */
export function ModerationDecisionCard({
  rejection,
  status,
  confidenceScore,
  technical,
}: {
  rejection: SpotRejection | null | undefined;
  status?: string | null;
  /** Supporting AI confidence only — never replaces the decision. Hidden when absent. */
  confidenceScore?: number | null;
  /** Optional additive technical evidence already available to the client. */
  technical?: DecisionTechnicalEvidence | null;
}) {
  const { t } = useTranslation('parking');
  if (!rejection) {
    return null;
  }

  const presentation = getRejectionPresentation({
    status: status ?? 'REJECTED',
    code: rejection.code,
    source: rejection.source,
    serverMessage: rejection.message,
    moderatorNote: rejection.moderatorNote,
    t,
  });

  const subtitle =
    presentation.variant === 'SYSTEM_MIGRATION'
      ? t('decision.subtitle.SYSTEM_MIGRATION')
      : null;

  const metadataItems = [
    { label: t('rejection.sourceLabel'), value: presentation.sourceLabel },
    ...(presentation.code
      ? [{ label: t('rejection.code'), value: presentation.code, mono: true }]
      : []),
    {
      label: t('rejection.rejectedAt'),
      value: formatInstant(rejection.rejectedAt),
    },
    ...(rejection.policyVersion
      ? [
          {
            label: t('rejection.policyVersion'),
            value: rejection.policyVersion,
            mono: true,
          },
        ]
      : []),
    ...(rejection.rejectedBy
      ? [{ label: t('rejection.rejectedBy'), value: rejection.rejectedBy, mono: true }]
      : []),
  ];

  const technicalRows = buildTechnicalRows({
    t,
    rejection,
    presentationCode: presentation.code,
    technical,
  });

  const showConfidence =
    presentation.variant === 'AI_POLICY' &&
    typeof confidenceScore === 'number' &&
    Number.isFinite(confidenceScore);

  return (
    <article
      className={cn(
        'flex flex-col gap-md rounded-2xl p-md ring-1',
        VARIANT_SURFACE[presentation.variant],
      )}
      data-testid="moderation-decision-card"
      data-rejection-variant={presentation.variant}
      aria-label={presentation.title}
    >
      <header className="flex flex-wrap items-start justify-between gap-sm">
        <div className="min-w-0 flex-1">
          <p className="m-0 flex items-center gap-xs text-title-md text-on-surface">
            <Icon
              name={VARIANT_ICON[presentation.variant]}
              className="text-[22px] leading-none"
              aria-hidden
            />
            <span>{presentation.title}</span>
          </p>
          {subtitle ? (
            <p
              className="m-0 mt-xs text-body-md text-on-surface-variant"
              data-testid="decision-subtitle"
            >
              {subtitle}
            </p>
          ) : null}
        </div>
        <DecisionBadge kind={presentation.variant} label={presentation.sourceLabel} />
      </header>

      <p
        className="m-0 text-body-lg text-on-surface"
        data-testid="spot-rejection-message"
      >
        {presentation.message}
      </p>

      <ModeratorNote note={presentation.moderatorNote} />

      {showConfidence ? (
        <section
          className="rounded-lg bg-surface/70 px-md py-sm"
          data-testid="decision-confidence"
          aria-label={t('decision.confidence.aria')}
        >
          <p className="m-0 text-label-sm text-on-surface-variant">
            {t('decision.confidence.title')}
          </p>
          <p className="m-0 mt-xs text-title-md tabular-nums text-on-surface">
            {formatConfidencePercent(confidenceScore)}
          </p>
          <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
            {t('decision.confidence.supporting')}
          </p>
        </section>
      ) : null}

      <DecisionMetadata items={metadataItems} />

      <TechnicalDetailsAccordion rows={technicalRows} />
    </article>
  );
}

function formatConfidencePercent(score: number): string {
  const normalized = score <= 1 ? score * 100 : score;
  return `${Math.round(normalized)}%`;
}

function buildTechnicalRows({
  t,
  rejection,
  presentationCode,
  technical,
}: {
  t: (key: string) => string;
  rejection: SpotRejection;
  presentationCode: string | null;
  technical?: DecisionTechnicalEvidence | null;
}): TechnicalDetailRow[] {
  const findings =
    technical?.findings
      ?.map((item) => item.trim())
      .filter(Boolean)
      .filter((item) => !/prompt|stack\s*trace/i.test(item)) ?? [];

  return [
    ...(technical?.provider
      ? [{ label: t('decision.technical.provider'), value: technical.provider }]
      : []),
    ...(technical?.model
      ? [{ label: t('decision.technical.model'), value: technical.model, mono: true }]
      : []),
    ...(technical?.providerReason
      ? [
          {
            label: t('decision.technical.providerReason'),
            value: technical.providerReason,
            mono: true,
          },
        ]
      : []),
    ...(typeof technical?.providerConfidence === 'number'
      ? [
          {
            label: t('decision.technical.providerConfidence'),
            value: formatConfidencePercent(technical.providerConfidence),
          },
        ]
      : []),
    ...(rejection.policyVersion
      ? [
          {
            label: t('rejection.policyVersion'),
            value: rejection.policyVersion,
            mono: true,
          },
        ]
      : []),
    ...(presentationCode
      ? [{ label: t('rejection.code'), value: presentationCode, mono: true }]
      : []),
    {
      label: t('decision.technical.validatedAt'),
      value: formatInstant(rejection.rejectedAt),
    },
    ...(technical?.evidenceId
      ? [
          {
            label: t('decision.technical.evidenceId'),
            value: technical.evidenceId,
            mono: true,
          },
        ]
      : []),
    ...(findings.length > 0
      ? [
          {
            label: t('decision.technical.findings'),
            value: findings.join(', '),
            mono: true,
          },
        ]
      : []),
    ...(rejection.source
      ? [{ label: t('rejection.sourceLabel'), value: rejection.source, mono: true }]
      : []),
  ];
}
