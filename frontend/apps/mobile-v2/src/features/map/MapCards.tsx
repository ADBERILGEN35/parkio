import { StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Glass } from '@/components/ui/Glass';
import { PulseMotif } from '@/components/ui/PulseMotif';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

/** Inline location-permission card over the map (pen `x6vdz`). */
export function LocationPermissionCard({
  canAskAgain,
  onAllow,
  onOpenSettings,
  onDismiss,
}: {
  canAskAgain: boolean;
  onAllow: () => void;
  onOpenSettings: () => void;
  onDismiss: () => void;
}) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  return (
    <Glass radius={20} contentStyle={styles.cardContent}>
      <View style={styles.iconWrap}>
        <PulseMotif size={72} rings={2} animated={false} style={styles.pulse} />
        <View style={[styles.iconBubble, { backgroundColor: colors.primaryFixed }]}>
          <MaterialCommunityIcons name="map-marker-off-outline" size={22} color={colors.primary} />
        </View>
      </View>
      <AppText variant="titleMd" align="center">
        {t('map.permission.title')}
      </AppText>
      <AppText variant="bodySm" align="center" color={colors.onSurfaceVariant}>
        {t('map.permission.body')}
      </AppText>
      <Button
        label={canAskAgain ? t('common.allow') : t('map.permission.openSettings')}
        size="md"
        style={styles.centerCta}
        onPress={canAskAgain ? onAllow : onOpenSettings}
      />
      <Button
        label={t('common.notNow')}
        variant="ghost"
        size="sm"
        style={styles.centerCta}
        onPress={onDismiss}
      />
    </Glass>
  );
}

/** Daily view-limit reached (gentle wall, brief §9.1). */
export function ViewLimitCard({
  level,
  limit,
  onLevelUp,
}: {
  level: number;
  limit: number;
  onLevelUp: () => void;
}) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  return (
    <Glass radius={20} contentStyle={styles.cardContent}>
      <View style={[styles.iconBubble, { backgroundColor: colors.surfaceContainer2 }]}>
        <MaterialCommunityIcons name="eye-off-outline" size={22} color={colors.tertiary} />
      </View>
      <AppText variant="titleMd" align="center">
        {t('map.viewLimit.title')}
      </AppText>
      <AppText variant="bodySm" align="center" color={colors.onSurfaceVariant}>
        {t('map.viewLimit.body', { level, limit })}
      </AppText>
      <Button
        label={t('map.viewLimit.cta')}
        size="md"
        variant="tonal"
        style={styles.centerCta}
        onPress={onLevelUp}
      />
    </Glass>
  );
}

/** Map empty state — pulse + first-to-share CTA. */
export function MapEmptyCard({ onShare }: { onShare: () => void }) {
  const theme = useTheme();
  const t = useT();
  return (
    <Glass radius={20} contentStyle={styles.cardContent}>
      <PulseMotif size={84} rings={3} />
      <AppText variant="titleMd" align="center">
        {t('map.empty.title')}
      </AppText>
      <Button label={t('map.empty.cta')} size="md" style={styles.centerCta} onPress={onShare} />
      <View style={{ height: 2 }} />
      <AppText variant="labelSm" color={theme.colors.onSurfaceVariant} align="center">
        {t('profile.about.stage')}
      </AppText>
    </Glass>
  );
}

const styles = StyleSheet.create({
  cardContent: { padding: 18, gap: 8, alignItems: 'center' },
  // Non-block buttons self-align flex-start; recenter inside the card column.
  centerCta: { alignSelf: 'center' },
  iconWrap: { width: 76, height: 76, alignItems: 'center', justifyContent: 'center' },
  pulse: { position: 'absolute' },
  iconBubble: {
    width: 44,
    height: 44,
    borderRadius: 22,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
