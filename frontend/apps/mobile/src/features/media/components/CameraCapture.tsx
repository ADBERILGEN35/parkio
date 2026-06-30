import { CameraView, type CameraType } from 'expo-camera';
import { useRef, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui';
import { HIT_SLOP, MIN_TOUCH_TARGET, useTheme } from '@/theme';
import type { LocalAsset } from '../types';

export interface CameraCaptureProps {
  /** Called with the captured frame as a local asset. */
  onCapture: (asset: LocalAsset) => void;
  /** Dismiss the camera without capturing. */
  onClose: () => void;
}

/**
 * In-app camera built on `expo-camera`'s {@link CameraView}. Renders a live
 * preview with a capture shutter, a close affordance, and a front/back toggle.
 * Permission is guaranteed granted by the parent before this mounts. The shutter
 * is disabled while a capture is in flight to prevent double-fires.
 */
export function CameraCapture({ onCapture, onClose }: CameraCaptureProps) {
  const theme = useTheme();
  const cameraRef = useRef<CameraView>(null);
  const [facing, setFacing] = useState<CameraType>('back');
  const [capturing, setCapturing] = useState(false);

  const takePicture = async () => {
    if (capturing) return;
    setCapturing(true);
    try {
      const picture = await cameraRef.current?.takePictureAsync({ quality: 1 });
      if (picture) {
        onCapture({ uri: picture.uri, width: picture.width, height: picture.height, temporary: true });
      }
    } finally {
      setCapturing(false);
    }
  };

  return (
    <View style={styles.container} testID="camera-capture">
      <CameraView ref={cameraRef} style={StyleSheet.absoluteFill} facing={facing} />

      <View style={[styles.topBar, { paddingTop: theme.spacing.lg }]}>
        <Pressable
          onPress={onClose}
          hitSlop={HIT_SLOP}
          accessibilityRole="button"
          accessibilityLabel="Close camera"
          style={styles.iconButton}
        >
          <AppText variant="subtitle" tone="inverse">
            ✕
          </AppText>
        </Pressable>
        <Pressable
          onPress={() => setFacing((f) => (f === 'back' ? 'front' : 'back'))}
          hitSlop={HIT_SLOP}
          accessibilityRole="button"
          accessibilityLabel="Switch camera"
          style={styles.iconButton}
        >
          <AppText variant="subtitle" tone="inverse">
            ⟲
          </AppText>
        </Pressable>
      </View>

      <View style={styles.bottomBar}>
        <Pressable
          onPress={takePicture}
          disabled={capturing}
          accessibilityRole="button"
          accessibilityLabel="Take photo"
          accessibilityState={{ disabled: capturing, busy: capturing }}
          style={[styles.shutter, { borderColor: theme.colors.onPrimary, opacity: capturing ? 0.5 : 1 }]}
        >
          <View style={[styles.shutterInner, { backgroundColor: theme.colors.onPrimary }]} />
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#000' },
  topBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
  },
  iconButton: {
    minWidth: MIN_TOUCH_TARGET,
    minHeight: MIN_TOUCH_TARGET,
    alignItems: 'center',
    justifyContent: 'center',
  },
  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    alignItems: 'center',
    paddingBottom: 40,
  },
  shutter: {
    width: 76,
    height: 76,
    borderRadius: 38,
    borderWidth: 4,
    alignItems: 'center',
    justifyContent: 'center',
  },
  shutterInner: { width: 60, height: 60, borderRadius: 30 },
});
