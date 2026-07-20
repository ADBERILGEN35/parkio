import type { ReactNode } from 'react';
import { Modal, Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Animated, { FadeIn, FadeOut, SlideInDown, SlideOutDown } from 'react-native-reanimated';
import { AppText } from './AppText';
import { radius, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface SheetProps {
  visible: boolean;
  onClose: () => void;
  title?: string;
  children: ReactNode;
}

/**
 * Action bottom sheet (24px top radius, drag handle, ambient-deep shadow).
 * Modal-based so it works from any screen without a host; the gesture-driven
 * map spot sheet uses @gorhom/bottom-sheet separately.
 */
export function Sheet({ visible, onClose, title, children }: SheetProps) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();

  return (
    <Modal
      visible={visible}
      transparent
      animationType="none"
      statusBarTranslucent
      navigationBarTranslucent
      onRequestClose={onClose}
    >
      <View style={styles.host}>
        <Animated.View entering={FadeIn.duration(200)} exiting={FadeOut.duration(150)} style={StyleSheet.absoluteFill}>
          <Pressable
            style={[StyleSheet.absoluteFill, { backgroundColor: theme.colors.scrim }]}
            onPress={onClose}
            accessibilityRole="button"
            accessibilityLabel="close"
          />
        </Animated.View>
        <Animated.View
          entering={SlideInDown.duration(280)}
          exiting={SlideOutDown.duration(200)}
          style={[
            styles.sheet,
            {
              backgroundColor: theme.colors.surface,
              paddingBottom: insets.bottom + 16,
            },
            shadows.ambientDeep,
          ]}
        >
          <View style={[styles.handle, { backgroundColor: theme.colors.outlineVariant }]} />
          {title ? (
            <AppText variant="titleLg" style={styles.title}>
              {title}
            </AppText>
          ) : null}
          {children}
        </Animated.View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  host: { flex: 1, justifyContent: 'flex-end' },
  sheet: {
    borderTopLeftRadius: radius.sheet,
    borderTopRightRadius: radius.sheet,
    paddingHorizontal: 20,
    paddingTop: 10,
    gap: 12,
  },
  handle: {
    alignSelf: 'center',
    width: 36,
    height: 4,
    borderRadius: 2,
    marginBottom: 6,
  },
  title: { marginBottom: 4 },
});
