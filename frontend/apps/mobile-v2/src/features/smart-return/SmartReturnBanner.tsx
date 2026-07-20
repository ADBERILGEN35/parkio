import { Pressable, StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { SmartReturnSettings } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Glass } from '@/components/ui/Glass';
import { IconButton } from '@/components/ui/IconButton';
import { useT } from '@/i18n/LocaleProvider';
import { formatClock } from '@/lib/time';
import { useTheme } from '@/theme/ThemeProvider';

export interface SmartReturnBannerProps {
  settings: SmartReturnSettings;
  onPress: () => void;
  onDismiss: () => void;
}

/**
 * Glass "today" strip over the map (brief §7.20/§12.8): active return plan
 * time + reminder lead; checking state while the return scan runs.
 */
export function SmartReturnBanner({ settings, onPress, onDismiss }: SmartReturnBannerProps) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;

  const checking = settings.todayStatus === 'RETURN_CHECK_IN_PROGRESS';
  const time = settings.todayExpectedReturnAt ? formatClock(settings.todayExpectedReturnAt) : '';

  return (
    <Glass radius={16}>
      <Pressable
        onPress={onPress}
        accessibilityRole="button"
        accessibilityLabel={t('smartReturn.title')}
        style={styles.row}
      >
        <View style={[styles.iconBubble, { backgroundColor: colors.primaryFixed }]}>
          <MaterialCommunityIcons name="home-clock-outline" size={18} color={colors.primary} />
        </View>
        <View style={styles.labels}>
          <AppText variant="bodySm" numberOfLines={1} style={styles.title}>
            {t('smartReturn.today.title', { time })}
          </AppText>
          <AppText variant="labelSm" color={colors.onSurfaceVariant} numberOfLines={1}>
            {checking
              ? t('smartReturn.today.checking')
              : t('smartReturn.today.lead', { m: settings.reminderLeadMinutes })}
          </AppText>
        </View>
        <IconButton
          icon="close"
          size={30}
          variant="glassless"
          accessibilityLabel={t('common.close')}
          onPress={onDismiss}
        />
      </Pressable>
    </Glass>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 12,
    paddingVertical: 9,
  },
  iconBubble: {
    width: 34,
    height: 34,
    borderRadius: 17,
    alignItems: 'center',
    justifyContent: 'center',
  },
  labels: { flex: 1, gap: 1 },
  title: { fontFamily: 'Inter_600SemiBold' },
});
