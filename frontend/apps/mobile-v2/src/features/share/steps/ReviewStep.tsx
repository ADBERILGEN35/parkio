import { useMemo } from 'react';
import { StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { PublicSpot } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { SpotCard } from '@/components/spots/SpotCard';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';
import { useShareDraftStore } from '../state/shareDraftStore';

export interface ReviewStepProps {
  onRetryUpload: () => void;
}

/**
 * Step 4 — the spot exactly as it will appear (preview SpotCard from the
 * draft) + honest lifetime hint + upload-readiness state.
 */
export function ReviewStep({ onRetryUpload }: ReviewStepProps) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const draft = useShareDraftStore();

  // Local view model shaped like the API payload — preview only, never sent.
  const previewSpot: PublicSpot = useMemo(() => {
    const nowIso = new Date().toISOString();
    return {
      id: 'preview',
      mediaId: draft.mediaId ?? 'preview',
      latitude: draft.location?.latitude ?? 0,
      longitude: draft.location?.longitude ?? 0,
      addressText: draft.addressText.trim() || null,
      description: draft.description.trim() || null,
      manualLocationEdited: draft.manualLocationEdited,
      suitableVehicleTypes: draft.vehicleTypes.length > 0 ? draft.vehicleTypes : ['ANY'],
      parkingContext: draft.parkingContext,
      legalStatus: draft.legalStatus ?? 'UNCERTAIN',
      violationReasons: draft.violationReasons,
      status: 'ACTIVE',
      expiresAt: nowIso,
      createdAt: nowIso,
      updatedAt: nowIso,
    };
  }, [draft]);

  const uploadPending = draft.uploadPhase === 'uploading' || draft.uploadPhase === 'scanning';
  const uploadFailed = draft.uploadPhase === 'failed';
  const uploadOffline = draft.uploadPhase === 'offline';

  return (
    <View style={styles.container}>
      <AppText variant="titleMd">{t('share.review.title')}</AppText>
      <SpotCard spot={previewSpot} photoUri={draft.photo?.uri ?? null} preview />
      <Card tone={1} padding={12}>
        <View style={styles.hintRow}>
          <MaterialCommunityIcons name="timer-outline" size={18} color={colors.primary} />
          <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.hintText}>
            {t('share.review.hint')}
          </AppText>
        </View>
      </Card>

      {uploadPending && (
        <View style={styles.uploadRow}>
          <MaterialCommunityIcons name="progress-upload" size={16} color={colors.onSurfaceVariant} />
          <AppText variant="bodySm" color={colors.onSurfaceVariant}>
            {draft.uploadPhase === 'scanning'
              ? t('share.upload.scanning')
              : t('share.upload.uploading', { percent: Math.round(draft.uploadProgress * 100) })}
          </AppText>
        </View>
      )}
      {uploadOffline && (
        <View style={styles.uploadRow}>
          <MaterialCommunityIcons name="wifi-off" size={16} color={colors.tertiary} />
          <AppText variant="bodySm" color={colors.tertiary} style={styles.hintText}>
            {t('share.upload.offline')}
          </AppText>
        </View>
      )}
      {uploadFailed && (
        <View style={styles.uploadRow}>
          <MaterialCommunityIcons name="alert-circle-outline" size={16} color={colors.error} />
          <AppText variant="bodySm" color={colors.error} style={styles.hintText}>
            {t('share.upload.failed')}
          </AppText>
          <Button label={t('common.retry')} variant="tonal" size="sm" block={false} onPress={onRetryUpload} />
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: 12 },
  hintRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  hintText: { flex: 1 },
  uploadRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
});
