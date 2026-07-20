import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Animated, { FadeInUp, FadeOutUp, LinearTransition } from 'react-native-reanimated';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { radius, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * Lightweight snackbar system (inverse-surface pills per brief §7.13).
 * `useToast().show('…')` — auto-dismisses, stacks up to 3.
 */
export type ToastTone = 'neutral' | 'success' | 'error';

interface ToastItem {
  id: number;
  message: string;
  tone: ToastTone;
}

interface ToastContextValue {
  show: (message: string, tone?: ToastTone) => void;
}

const ToastContext = createContext<ToastContextValue>({ show: () => undefined });

const TONE_ICON: Record<ToastTone, keyof typeof MaterialCommunityIcons.glyphMap> = {
  neutral: 'information-outline',
  success: 'check-circle-outline',
  error: 'alert-circle-outline',
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const counter = useRef(0);
  const insets = useSafeAreaInsets();
  const theme = useTheme();

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const show = useCallback(
    (message: string, tone: ToastTone = 'neutral') => {
      counter.current += 1;
      const id = counter.current;
      setToasts((current) => [...current.slice(-2), { id, message, tone }]);
      setTimeout(() => dismiss(id), 3500);
    },
    [dismiss],
  );

  const value = useMemo(() => ({ show }), [show]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <View
        pointerEvents="box-none"
        style={[styles.host, { top: insets.top + 8 }]}
        accessibilityLiveRegion="polite"
      >
        {toasts.map((toast) => (
          <Animated.View
            key={toast.id}
            entering={FadeInUp.duration(250)}
            exiting={FadeOutUp.duration(200)}
            layout={LinearTransition.duration(200)}
            style={[styles.toast, { backgroundColor: theme.colors.inverseSurface }, shadows.ambientDeep]}
          >
            <Pressable onPress={() => dismiss(toast.id)} style={styles.inner}>
              <MaterialCommunityIcons
                name={TONE_ICON[toast.tone]}
                size={18}
                color={
                  toast.tone === 'error'
                    ? '#FFB4AB'
                    : toast.tone === 'success'
                      ? '#6CF8BB'
                      : theme.colors.onInverseSurface
                }
              />
              <AppText
                variant="bodyMd"
                color={theme.colors.onInverseSurface}
                style={styles.message}
                numberOfLines={3}
              >
                {toast.message}
              </AppText>
            </Pressable>
          </Animated.View>
        ))}
      </View>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  return useContext(ToastContext);
}

const styles = StyleSheet.create({
  host: {
    position: 'absolute',
    left: 16,
    right: 16,
    alignItems: 'center',
    gap: 8,
    zIndex: 100,
  },
  toast: {
    borderRadius: radius.card,
    maxWidth: 480,
    width: '100%',
  },
  inner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  message: { flexShrink: 1 },
});
