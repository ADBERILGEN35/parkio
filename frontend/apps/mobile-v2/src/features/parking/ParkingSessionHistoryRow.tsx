import { StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { ParkingSessionResponse } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Chip } from '@/components/ui/Chip';
import { IconButton } from '@/components/ui/IconButton';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';
import {
  formatTerminalSessionDuration,
  formatTerminalSessionStarted,
  isTerminalParkingSessionStatus,
} from './parkingHistoryModel';

export interface ParkingSessionHistoryRowProps {
  session: ParkingSessionResponse;
  deleteDisabled: boolean;
  deleting: boolean;
  onDeletePress: (sessionId: string) => void;
}

/** Terminal history row — never shows coordinates or session UUID. */
export function ParkingSessionHistoryRow({
  session,
  deleteDisabled,
  deleting,
  onDeletePress,
}: ParkingSessionHistoryRowProps) {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const { colors } = theme;

  if (!isTerminalParkingSessionStatus(session.status)) {
    return null;
  }

  const statusLabel =
    session.status === 'COMPLETED'
      ? t('parkingSession.history.status.completed')
      : t('parkingSession.history.status.cancelled');
  const startedLabel = formatTerminalSessionStarted(session.startedAt, locale);
  const durationLabel = formatTerminalSessionDuration(session.startedAt, session.endedAt, locale);
  const sourceLabel =
    session.parkingSource === 'COMMUNITY'
      ? t('parkingSession.history.source.community')
      : t('parkingSession.history.source.manual');

  const a11yParts = [statusLabel, startedLabel, durationLabel, sourceLabel].filter(Boolean);
  const accessibilityLabel = a11yParts.join(', ');

  return (
    <View
      style={[styles.row, { backgroundColor: colors.surface }]}
      accessibilityRole="summary"
      accessibilityLabel={accessibilityLabel}
      testID={`parking-history-row-${session.status}`}
    >
      <View style={[styles.iconBubble, { backgroundColor: colors.surfaceContainer1 }]}>
        <MaterialCommunityIcons
          name={session.status === 'COMPLETED' ? 'check-circle-outline' : 'close-circle-outline'}
          size={18}
          color={session.status === 'COMPLETED' ? colors.primary : colors.tertiary}
          accessible={false}
        />
      </View>
      <View style={styles.labels}>
        <View style={styles.titleRow}>
          <AppText variant="bodyMd" numberOfLines={1} style={styles.flex}>
            {startedLabel || t('parkingSession.history.unknownDate')}
          </AppText>
          <Chip label={statusLabel} size="sm" selected={session.status === 'COMPLETED'} />
        </View>
        <AppText variant="labelSm" color={colors.onSurfaceVariant} numberOfLines={1}>
          {[durationLabel, sourceLabel].filter(Boolean).join(' · ')}
        </AppText>
      </View>
      <IconButton
        icon="trash-can-outline"
        size={40}
        variant="destructiveGhost"
        disabled={deleteDisabled || deleting}
        accessibilityLabel={t('parkingSession.history.delete.a11y', { status: statusLabel })}
        onPress={() => onDeletePress(session.id)}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 14,
    minHeight: 64,
  },
  iconBubble: {
    width: 34,
    height: 34,
    borderRadius: 17,
    alignItems: 'center',
    justifyContent: 'center',
  },
  labels: { flex: 1, gap: 2 },
  titleRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  flex: { flex: 1 },
});