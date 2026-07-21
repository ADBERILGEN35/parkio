import type { ReactNode } from 'react';
import { useEffect, useId } from 'react';
import { Modal, Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppText } from './AppText';
import { radius, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface SheetProps {
  visible: boolean;
  onClose: () => void;
  title?: string;
  children: ReactNode;
}

/** Live mount counter for diagnostic builds (no PII). */
let sheetMountCount = 0;

/**
 * Action bottom sheet. Modal-based.
 *
 * Hit-testing (Android): backdrop and panel are non-overlapping flex siblings.
 * Reanimated wrappers are intentionally avoided on the press path — entering
 * animations on Animated.View have swallowed Pressable onPress inside Modal on
 * physical devices even when UIAutomator reported the buttons as clickable.
 */
export function Sheet({ visible, onClose, title, children }: SheetProps) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const instanceId = useId();

  useEffect(() => {
    sheetMountCount += 1;
    console.info(`[ShareSheet] Sheet mounted id=${instanceId} count=${sheetMountCount} visible=${visible}`);
    return () => {
      sheetMountCount = Math.max(0, sheetMountCount - 1);
      console.info(`[ShareSheet] Sheet unmounted id=${instanceId} count=${sheetMountCount}`);
    };
  }, [instanceId]);

  useEffect(() => {
    if (visible) {
      console.info(`[ShareSheet] Sheet visible=true id=${instanceId} mounts=${sheetMountCount}`);
    }
  }, [visible, instanceId]);

  const handleBackdropPress = () => {
    console.info('[ShareSheet] backdrop press');
    console.info('[ShareSheet] close requested');
    onClose();
  };

  const handleRequestClose = () => {
    console.info('[ShareSheet] close requested');
    onClose();
  };

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      navigationBarTranslucent
      onRequestClose={handleRequestClose}
    >
      <View style={styles.host} testID="sheet-host" collapsable={false}>
        <View style={styles.backdropSlot} collapsable={false}>
          <Pressable
            testID="sheet-backdrop"
            style={[styles.backdrop, { backgroundColor: theme.colors.scrim }]}
            onPress={handleBackdropPress}
            accessibilityRole="button"
            accessibilityLabel="close"
          />
        </View>
        <View
          testID="sheet-panel"
          collapsable={false}
          style={[
            styles.sheet,
            shadows.ambientDeep,
            {
              backgroundColor: theme.colors.surface,
              paddingBottom: insets.bottom + 16,
              elevation: 16,
            },
          ]}
        >
          <View style={[styles.handle, { backgroundColor: theme.colors.outlineVariant }]} />
          {title ? (
            <AppText variant="titleLg" style={styles.title}>
              {title}
            </AppText>
          ) : null}
          {children}
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  host: {
    flex: 1,
  },
  backdropSlot: {
    flex: 1,
  },
  backdrop: {
    flex: 1,
  },
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
