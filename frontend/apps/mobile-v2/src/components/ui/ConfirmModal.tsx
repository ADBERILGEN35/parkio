import { Modal, Pressable, StyleSheet, View } from 'react-native';
import Animated, { FadeIn, FadeOut, ZoomIn } from 'react-native-reanimated';
import { AppText } from './AppText';
import { Button, type ButtonVariant } from './Button';
import { radius, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface ConfirmModalProps {
  visible: boolean;
  title: string;
  body?: string;
  confirmLabel: string;
  cancelLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
  confirmVariant?: ButtonVariant;
  loading?: boolean;
}

/** Centered confirm dialog (24px radius, ambient-deep) per the pen kit. */
export function ConfirmModal({
  visible,
  title,
  body,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onCancel,
  confirmVariant = 'primary',
  loading,
}: ConfirmModalProps) {
  const theme = useTheme();
  return (
    <Modal
      visible={visible}
      transparent
      animationType="none"
      statusBarTranslucent
      navigationBarTranslucent
      onRequestClose={onCancel}
    >
      <View style={styles.host}>
        <Animated.View entering={FadeIn.duration(180)} exiting={FadeOut.duration(150)} style={StyleSheet.absoluteFill}>
          <Pressable
            style={[StyleSheet.absoluteFill, { backgroundColor: theme.colors.scrim }]}
            onPress={loading ? undefined : onCancel}
          />
        </Animated.View>
        <Animated.View
          entering={ZoomIn.springify().damping(18).stiffness(220)}
          accessibilityRole="summary"
          accessibilityViewIsModal
          accessibilityLabel={title}
          style={[
            styles.card,
            { backgroundColor: theme.colors.surface, borderRadius: radius.modal },
            shadows.ambientDeep,
          ]}
        >
          <AppText variant="titleLg">{title}</AppText>
          {body ? (
            <AppText variant="bodyMd" color={theme.colors.onSurfaceVariant}>
              {body}
            </AppText>
          ) : null}
          <View style={styles.actions}>
            <Button label={confirmLabel} variant={confirmVariant} onPress={onConfirm} loading={loading} />
            <Button label={cancelLabel} variant="ghost" onPress={onCancel} disabled={loading} />
          </View>
        </Animated.View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  host: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  card: {
    width: '100%',
    maxWidth: 400,
    padding: 24,
    gap: 10,
  },
  actions: { marginTop: 12, gap: 4 },
});
