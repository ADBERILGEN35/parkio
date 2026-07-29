import { View } from 'react-native';
import type { MaterialCommunityIcons } from '@expo/vector-icons';
import { Badge } from '@/components/ui/Badge';
import type { RejectionPresentationVariant } from '@/lib/getRejectionPresentation';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

export type DecisionBadgeKind =
  | RejectionPresentationVariant
  | 'ACCEPTED'
  | 'REJECTED'
  | 'REVIEW'
  | 'SYSTEM_MIGRATION_STATUS';

type IconName = keyof typeof MaterialCommunityIcons.glyphMap;

const KIND_ICON: Record<DecisionBadgeKind, IconName> = {
  AI_POLICY: 'robot-outline',
  MODERATOR: 'account-outline',
  SYSTEM_MIGRATION: 'cog-outline',
  UNKNOWN: 'help-circle-outline',
  ACCEPTED: 'check-circle-outline',
  REJECTED: 'close-circle-outline',
  REVIEW: 'clipboard-text-outline',
  SYSTEM_MIGRATION_STATUS: 'cog-outline',
};

/**
 * Soft decision/status badge using the shared {@link Badge} primitive
 * (MaterialCommunityIcons — never emoji).
 */
export function DecisionBadge({
  kind,
  label,
}: {
  kind: DecisionBadgeKind;
  /** Optional override; defaults to i18n `decision.badge.*`. */
  label?: string;
}) {
  const t = useT();
  const theme = useTheme();
  const { colors } = theme;
  const text = label ?? t(`decision.badge.${kind}`);
  const { fg, bg } = toneColors(kind, theme.mode === 'dark', colors);

  return (
    <View accessibilityLabel={text} testID={`decision-badge-${kind}`}>
      <Badge label={text} icon={KIND_ICON[kind]} fg={fg} bg={bg} size="sm" />
    </View>
  );
}

function toneColors(
  kind: DecisionBadgeKind,
  dark: boolean,
  colors: ReturnType<typeof useTheme>['colors'],
): { fg: string; bg: string } {
  switch (kind) {
    case 'AI_POLICY':
    case 'REJECTED':
      return { fg: colors.error, bg: dark ? colors.errorContainer : colors.errorContainer };
    case 'MODERATOR':
    case 'REVIEW':
      return {
        fg: dark ? colors.tertiary : colors.tertiary,
        bg: dark ? colors.tertiaryContainer : '#7F4F001A',
      };
    case 'ACCEPTED':
      return {
        fg: colors.secondary,
        bg: dark ? colors.secondaryContainer : '#006C491A',
      };
    case 'SYSTEM_MIGRATION':
    case 'SYSTEM_MIGRATION_STATUS':
    case 'UNKNOWN':
    default:
      return {
        fg: colors.onSurfaceVariant,
        bg: dark ? '#FFFFFF14' : colors.surfaceContainer1,
      };
  }
}
