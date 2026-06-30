import { Image, StyleSheet, View } from 'react-native';
import { Button } from '@/components/ui';
import { useTheme } from '@/theme';
import type { LocalAsset } from '../types';

export interface ImagePreviewProps {
  asset: LocalAsset;
  /** Re-open the camera for a new shot. */
  onRetake: () => void;
  /** Re-open the gallery / source chooser. */
  onChooseAnother: () => void;
  /** Confirm this image and start the upload. */
  onConfirm: () => void;
  busy?: boolean;
}

/**
 * Confirmation step after capture/pick. Shows the chosen photo full-bleed with
 * three clear actions: confirm (upload), retake, or choose another. All controls
 * meet the 44pt touch target via {@link Button}.
 */
export function ImagePreview({ asset, onRetake, onChooseAnother, onConfirm, busy }: ImagePreviewProps) {
  const theme = useTheme();
  return (
    <View style={styles.container} testID="image-preview">
      <View style={[styles.imageFrame, { borderRadius: theme.radius.xl, backgroundColor: theme.colors.surfaceMuted }]}>
        <Image
          source={{ uri: asset.uri }}
          style={styles.image}
          resizeMode="cover"
          accessibilityRole="image"
          accessibilityLabel="Preview of the parking spot photo you captured"
        />
      </View>
      <View style={styles.actions}>
        <Button label="Use this photo" onPress={onConfirm} loading={busy} />
        <View style={styles.secondaryRow}>
          <Button label="Retake" variant="secondary" onPress={onRetake} disabled={busy} fullWidth={false} />
          <Button
            label="Choose another"
            variant="ghost"
            onPress={onChooseAnother}
            disabled={busy}
            fullWidth={false}
          />
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, gap: 16 },
  imageFrame: { flex: 1, overflow: 'hidden' },
  image: { width: '100%', height: '100%' },
  actions: { gap: 12 },
  secondaryRow: { flexDirection: 'row', justifyContent: 'center', gap: 12 },
});
