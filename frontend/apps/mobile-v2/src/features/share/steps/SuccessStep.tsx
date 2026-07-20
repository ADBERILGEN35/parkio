import { StyleSheet, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { Spot } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { PulseMotif } from '@/components/ui/PulseMotif';
import { FreshnessRing } from '@/components/spots/FreshnessRing';
import { useT } from '@/i18n/LocaleProvider';
import { radius, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface SuccessStepProps {
  spot: Spot;
  onGoMySpots: () => void;
  onBackToMap: () => void;
}

const UPLOAD_POINTS = 5;

/**
 * Publish success (pen `RL6Sn`): celebration gradient (+5 puan) — the ONLY
 * blue-glow moment — then the honest pending-validation state.
 */
export function SuccessStep({ spot, onGoMySpots, onBackToMap }: SuccessStepProps) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const pending = spot.status === 'PENDING_VALIDATION' || spot.status === 'PENDING_REVIEW';

  return (
    <View style={styles.container}>
      <View style={[styles.banner, shadows.blueGlow]}>
        <LinearGradient
          colors={['#0050CB', '#0066FF']}
          start={{ x: 0, y: 0 }}
          end={{ x: 1, y: 1 }}
          style={styles.gradient}
        >
          <PulseMotif size={190} rings={3} color="#FFFFFF" core={false} style={styles.bannerPulse} />
          <AppText variant="headlineMd" color="#FFFFFF">
            {t('share.success.title', { points: UPLOAD_POINTS })}
          </AppText>
          <AppText variant="bodyMd" color="rgba(255,255,255,0.85)">
            {t('share.success.subtitle')}
          </AppText>
          <View style={styles.pointsChip}>
            <MaterialCommunityIcons name="star-four-points" size={13} color="#FFFFFF" />
            <AppText variant="labelSm" color="#FFFFFF">
              +{UPLOAD_POINTS} {t('common.points')}
            </AppText>
          </View>
        </LinearGradient>
      </View>

      {pending ? (
        <Card padding={16}>
          <View style={styles.pendingRow}>
            <FreshnessRing fraction={null} size={44} strokeWidth={2.5}>
              <MaterialCommunityIcons name="shield-search" size={17} color={colors.onSurfaceVariant} />
            </FreshnessRing>
            <View style={styles.pendingLabels}>
              <AppText variant="titleMd">{t('share.success.pendingTitle')}</AppText>
              <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                {t('share.success.pendingBody')}
              </AppText>
            </View>
          </View>
        </Card>
      ) : null}

      <View style={styles.actions}>
        <Button label={t('share.success.goMySpots')} variant="tonal" onPress={onGoMySpots} />
        <Button label={t('share.success.backToMap')} variant="ghost" onPress={onBackToMap} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: 14, paddingTop: 22 },
  banner: { borderRadius: radius.sheet },
  gradient: {
    borderRadius: radius.sheet,
    padding: 22,
    gap: 6,
    overflow: 'hidden',
  },
  bannerPulse: { position: 'absolute', right: -50, top: -46 },
  pointsChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    backgroundColor: 'rgba(255,255,255,0.18)',
    alignSelf: 'flex-start',
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 999,
    marginTop: 8,
  },
  pendingRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  pendingLabels: { flex: 1, gap: 2 },
  actions: { gap: 6, marginTop: 8 },
});
