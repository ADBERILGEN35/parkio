import { StyleSheet, View } from 'react-native';
import { AppText, Button } from '@/components/ui';
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
  return (
    <View style={styles.container} testID="media-source-sheet">
      <View style={styles.copy}>
        <AppText variant="heading">Add a photo of the spot</AppText>
        <AppText variant="body" tone="muted">
          A clear photo helps other drivers find and trust the spot. Take one now or pick from your gallery.
        </AppText>
      </View>
      <View style={styles.actions}>
        <Button
          label="Take photo"
          onPress={() => onPick('camera')}
          accessibilityHint="Opens the camera to capture a new photo"
        />
        <Button
          label="Choose from gallery"
          variant="secondary"
          onPress={() => onPick('library')}
          accessibilityHint="Opens your photo library to pick an existing photo"
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
