import { StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Sheet } from '@/components/ui/Sheet';
import { useT } from '@/i18n/LocaleProvider';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';
import { useTheme } from '@/theme/ThemeProvider';

export type ShareSource = 'camera' | 'gallery';

export interface ShareSourceSheetProps {
  visible: boolean;
  onClose: () => void;
  onPick: (source: ShareSource) => void;
  onResume: () => void;
}

/**
 * "Yer paylaş" source sheet (pen `bGJ1T`): camera-first, gallery secondary,
 * EXIF privacy footnote. When a resumable draft exists, offer continue/discard
 * first (brief §12.5.10).
 */
export function ShareSourceSheet({ visible, onClose, onPick, onResume }: ShareSourceSheetProps) {
  const theme = useTheme();
  const t = useT();
  const resumable = useShareDraftStore((s) => s.resumableDraft);
  const hasContent = useShareDraftStore((s) => Boolean(s.photo || s.description || s.addressText));
  const reset = useShareDraftStore((s) => s.reset);

  const showResume = resumable && hasContent;

  return (
    <Sheet visible={visible} onClose={onClose} title={t('share.title')}>
      {showResume ? (
        <View style={styles.resumeBlock}>
          <AppText variant="titleMd">{t('share.draft.resumeTitle')}</AppText>
          <AppText variant="bodySm" color={theme.colors.onSurfaceVariant}>
            {t('share.draft.resumeBody')}
          </AppText>
          <Button label={t('share.draft.resume')} onPress={onResume} />
          <Button
            label={t('share.draft.discard')}
            variant="ghost"
            onPress={() => {
              reset();
            }}
          />
        </View>
      ) : (
        <View style={styles.actions}>
          <Button label={t('share.source.camera')} icon="camera-outline" onPress={() => onPick('camera')} />
          <Button
            label={t('share.source.gallery')}
            icon="image-multiple-outline"
            variant="tonal"
            onPress={() => onPick('gallery')}
          />
          <View style={styles.footnote}>
            <MaterialCommunityIcons
              name="shield-check-outline"
              size={14}
              color={theme.colors.onSurfaceVariant}
            />
            <AppText variant="bodySm" color={theme.colors.onSurfaceVariant} style={styles.footnoteText}>
              {t('share.source.exifNote')}
            </AppText>
          </View>
        </View>
      )}
    </Sheet>
  );
}

const styles = StyleSheet.create({
  actions: { gap: 10, paddingTop: 4 },
  resumeBlock: { gap: 10, paddingTop: 4 },
  footnote: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    paddingTop: 6,
    paddingBottom: 2,
  },
  footnoteText: { flexShrink: 1 },
});
