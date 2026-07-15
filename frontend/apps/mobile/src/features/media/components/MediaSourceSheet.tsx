import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppText, Button } from '@/components/ui';
import { useLocale } from '@/i18n/LocaleProvider';
import type { MediaSource } from '../types';

export interface MediaSourceSheetProps {
  onPick: (source: MediaSource) => void;
}

/**
 * Entry step: choose where the photo comes from. Two large, clearly-labelled
 * targets — the native camera or the photo library. Deliberately simple so the
 * capture flow starts with a single decision.
 */
export function MediaSourceSheet({ onPick }: MediaSourceSheetProps) {
  const { t } = useLocale();
  const insets = useSafeAreaInsets();

  return (
    <View
      style={[styles.container, { paddingBottom: Math.max(insets.bottom, 12) }]}
      testID="media-source-sheet"
    >
      <View style={styles.copy}>
        <AppText variant="heading">{t('Add a photo of the spot')}</AppText>
        <AppText variant="body" tone="muted">
          {t(
            'A clear photo helps other drivers find and trust the spot. Take one now or pick from your gallery.',
          )}
        </AppText>
      </View>
      <View style={styles.actions}>
        <Button
          label={t('Take photo')}
          onPress={() => onPick('camera')}
          accessibilityHint={t('Opens the camera to capture a new photo')}
        />
        <Button
          label={t('Choose from gallery')}
          variant="secondary"
          onPress={() => onPick('library')}
          accessibilityHint={t('Opens your photo library to pick an existing photo')}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'space-between', gap: 24 },
  copy: { gap: 8, paddingTop: 8 },
  actions: { gap: 12 },
});
