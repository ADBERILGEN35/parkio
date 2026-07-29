import { StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { ComponentProps } from 'react';
import type { SpotRejection } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Card } from '@/components/ui/Card';
import { DecisionBadge } from './DecisionBadge';
import { DecisionMetadata, type DecisionMetadataItem } from './DecisionMetadata';
import { ModeratorNote } from './ModeratorNote';
import { TechnicalDetailsAccordion, type TechnicalDetailRow } from './TechnicalDetailsAccordion';
import {
  getRejectionPresentation,
  type RejectionPresentationVariant,
} from '@/lib/getRejectionPresentation';
import { useLocale, useT, type Translator } from '@/i18n/LocaleProvider';
import { formatDateTime } from '@/lib/time';
import { spacing } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export type DecisionTechnicalEvidence = {
  provider?: string | null;
  model?: string | null;
  providerReason?: string | null;
  providerConfidence?: number | null;
  evidenceId?: string | null;
  findings?: string[] | null;
};

type IconName = ComponentProps<typeof MaterialCommunityIcons>['name'];

const VARIANT_ICON: Record<RejectionPresentationVariant, IconName> = {
  AI_POLICY: 'robot-outline',
  MODERATOR: 'account-outline',
  SYSTEM_MIGRATION: 'cog-outline',
  UNKNOWN: 'help-circle-outline',
};

/**
 * Unified moderation decision card for AI, moderator, and system-migration outcomes.
 * Layout uses mobile-v2 Card / Badge / PressableScale — not a web layout port.
 */
export function DecisionCard({
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
  const t = useT();
  const { locale } = useLocale();
  const theme = useTheme();
  const { colors } = theme;

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
    presentation.variant === 'SYSTEM_MIGRATION' ? t('decision.subtitle.SYSTEM_MIGRATION') : null;

  const metadataItems: DecisionMetadataItem[] = [
    { label: t('rejection.sourceLabel'), value: presentation.sourceLabel },
    ...(presentation.code
      ? [{ label: t('rejection.code'), value: presentation.code, mono: true }]
      : []),
    {
      label: t('rejection.rejectedAt'),
      value: formatDateTime(rejection.rejectedAt, locale),
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
    locale,
    rejection,
    presentationCode: presentation.code,
    technical,
  });

  const showConfidence =
    presentation.variant === 'AI_POLICY' &&
    typeof confidenceScore === 'number' &&
    Number.isFinite(confidenceScore);

  const iconColor =
    presentation.tone === 'danger'
      ? colors.error
      : presentation.tone === 'warning'
        ? colors.tertiary
        : colors.onSurfaceVariant;

  return (
    <View
      testID="moderation-decision-card"
      accessibilityLabel={presentation.title}
      accessibilityHint={presentation.variant}
    >
      <Card
        tone={presentation.variant === 'SYSTEM_MIGRATION' ? 1 : 0}
        style={styles.card}
        shadow={presentation.variant !== 'SYSTEM_MIGRATION'}
      >
        <View style={styles.header}>
          <View style={styles.headerText}>
            <View style={styles.titleRow}>
              <MaterialCommunityIcons
                name={VARIANT_ICON[presentation.variant]}
                size={22}
                color={iconColor}
                accessibilityElementsHidden
                importantForAccessibility="no"
              />
              <AppText variant="titleMd" style={styles.title}>
                {presentation.title}
              </AppText>
            </View>
            {subtitle ? (
              <AppText variant="bodySm" color={colors.onSurfaceVariant} testID="decision-subtitle">
                {subtitle}
              </AppText>
            ) : null}
          </View>
          <DecisionBadge kind={presentation.variant} label={presentation.sourceLabel} />
        </View>

        <AppText variant="bodyLg" testID="spot-rejection-message">
          {presentation.message}
        </AppText>

        <ModeratorNote note={presentation.moderatorNote} />

        {showConfidence ? (
          <View
            style={[styles.confidence, { backgroundColor: colors.surfaceContainer1 }]}
            testID="decision-confidence"
            accessibilityLabel={t('decision.confidence.aria')}
          >
            <AppText variant="labelSm" color={colors.onSurfaceVariant}>
              {t('decision.confidence.title')}
            </AppText>
            <AppText variant="titleMd" tabular>
              {formatConfidencePercent(confidenceScore)}
            </AppText>
            <AppText variant="labelSm" color={colors.onSurfaceVariant}>
              {t('decision.confidence.supporting')}
            </AppText>
          </View>
        ) : null}

        <DecisionMetadata items={metadataItems} />

        <TechnicalDetailsAccordion rows={technicalRows} />
      </Card>
    </View>
  );
}

function formatConfidencePercent(score: number): string {
  const normalized = score <= 1 ? score * 100 : score;
  return `${Math.round(normalized)}%`;
}

function buildTechnicalRows({
  t,
  locale,
  rejection,
  presentationCode,
  technical,
}: {
  t: Translator;
  locale: 'tr' | 'en';
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
      value: formatDateTime(rejection.rejectedAt, locale),
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

const styles = StyleSheet.create({
  card: { gap: spacing.md },
  header: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  headerText: { flex: 1, gap: spacing.xs, minWidth: 0 },
  titleRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  title: { flexShrink: 1 },
  confidence: {
    gap: spacing.xs,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: 8,
  },
});
