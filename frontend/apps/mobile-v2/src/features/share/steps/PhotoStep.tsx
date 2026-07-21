import { Image, StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { isValidClaimedRegion } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { FreshnessRing } from '@/components/spots/FreshnessRing';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';
import { ClaimedRegionAnnotator } from '../components/ClaimedRegionAnnotator';
import { useShareDraftStore } from '../state/shareDraftStore';

export interface PhotoStepProps {
  onRetake: () => void;
  onPickGallery: () => void;
  onCancelUpload: () => void;
  onRetryUpload: () => void;
}

/**
 * Step 1 — the captured photo, claimed-space annotation, and live upload status.
 */
export function PhotoStep({ onRetake, onPickGallery, onCancelUpload, onRetryUpload }: PhotoStepProps) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const photo = useShareDraftStore((s) => s.photo);
  const setClaimedRegion = useShareDraftStore((s) => s.setClaimedRegion);
  const phase = useShareDraftStore((s) => s.uploadPhase);
  const progress = useShareDraftStore((s) => s.uploadProgress);
  const hasRegion = isValidClaimedRegion(photo?.claimedRegion);

  const status = (() => {
    if (!hasRegion) {
      return {
        icon: 'vector-square' as const,
        label: t('share.annotate.required'),
        tone: colors.onSurfaceVariant,
      };
    }
    switch (phase) {
      case 'uploading':
        return {
          icon: null,
          label: t('share.upload.uploading', { percent: Math.round(progress * 100) }),
          tone: colors.onSurfaceVariant,
        };
      case 'scanning':
        return { icon: 'shield-search' as const, label: t('share.upload.scanning'), tone: colors.onSurfaceVariant };
      case 'ready':
        return { icon: 'check-circle-outline' as const, label: t('share.upload.ready'), tone: colors.secondary };
      case 'failed':
        return { icon: 'alert-circle-outline' as const, label: t('share.upload.failed'), tone: colors.error };
      case 'offline':
        return { icon: 'wifi-off' as const, label: t('share.upload.offline'), tone: colors.tertiary };
      default:
        return { icon: 'progress-upload' as const, label: t('share.upload.preparing'), tone: colors.onSurfaceVariant };
    }
  })();

  return (
    <View style={styles.container}>
      <View style={[styles.photoCard, { backgroundColor: colors.surfaceContainer2 }]}>
        {photo ? (
          <>
            <Image source={{ uri: photo.uri }} style={styles.photo} resizeMode="contain" />
            <ClaimedRegionAnnotator
              imageWidth={photo.width}
              imageHeight={photo.height}
              value={photo.claimedRegion}
              onChange={(region) => setClaimedRegion(region)}
            />
          </>
        ) : (
          <View style={styles.photoFallback}>
            <MaterialCommunityIcons name="camera-outline" size={38} color={colors.outline} />
            <AppText variant="bodyMd" color={colors.onSurfaceVariant}>
              {t('share.photoMissing')}
            </AppText>
          </View>
        )}
      </View>

      {photo && (
        <Card tone={1} padding={14} style={styles.statusCard}>
          <View style={styles.statusRow}>
            {hasRegion && phase === 'uploading' ? (
              <FreshnessRing fraction={progress} size={28} strokeWidth={2.5} color={colors.primary} />
            ) : status.icon ? (
              <MaterialCommunityIcons name={status.icon} size={22} color={status.tone} />
            ) : null}
            <AppText variant="bodySm" color={status.tone} style={styles.statusLabel}>
              {status.label}
            </AppText>
            {hasRegion && phase === 'uploading' && (
              <Button label={t('common.cancel')} variant="ghost" size="sm" block={false} onPress={onCancelUpload} />
            )}
            {hasRegion && phase === 'failed' && (
              <Button label={t('common.retry')} variant="tonal" size="sm" block={false} onPress={onRetryUpload} />
            )}
          </View>
        </Card>
      )}

      <View style={styles.actions}>
        <Button
          label={t('share.camera.retake')}
          variant="tonal"
          size="md"
          icon="camera-outline"
          onPress={onRetake}
        />
        <Button
          label={t('share.source.gallery')}
          variant="ghost"
          size="md"
          icon="image-multiple-outline"
          onPress={onPickGallery}
        />
      </View>

      <View style={styles.footnote}>
        <MaterialCommunityIcons name="shield-check-outline" size={14} color={colors.onSurfaceVariant} />
        <AppText variant="labelSm" color={colors.onSurfaceVariant} style={styles.footnoteText}>
          {t('share.source.exifNote')}
        </AppText>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: 12 },
  photoCard: { height: 300, borderRadius: 20, overflow: 'hidden' },
  photo: { width: '100%', height: '100%' },
  photoFallback: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 8 },
  statusCard: {},
  statusRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  statusLabel: { flex: 1 },
  actions: { gap: 8 },
  footnote: { flexDirection: 'row', alignItems: 'center', gap: 6, justifyContent: 'center' },
  footnoteText: { flexShrink: 1 },
});
