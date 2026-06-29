import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { Animated, StyleSheet } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppText } from '@/components/ui/AppText';
import { useTheme } from '@/theme';

export type ToastTone = 'success' | 'error' | 'info';

interface ToastState {
  message: string;
  tone: ToastTone;
}

interface ToastApi {
  showToast: (message: string, tone?: ToastTone) => void;
  showError: (message: string) => void;
  showSuccess: (message: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

const VISIBLE_MS = 3200;

/**
 * Lightweight toast system. Uses RN's `Animated` (not Reanimated worklets) so it
 * is trivially testable and dependency-light. A single toast shows at a time;
 * a new toast replaces the current one.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<ToastState | null>(null);
  const [opacity] = useState(() => new Animated.Value(0));
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const hide = useCallback(() => {
    Animated.timing(opacity, { toValue: 0, duration: 180, useNativeDriver: true }).start(() =>
      setToast(null),
    );
  }, [opacity]);

  const showToast = useCallback(
    (message: string, tone: ToastTone = 'info') => {
      if (timer.current) clearTimeout(timer.current);
      setToast({ message, tone });
      Animated.timing(opacity, { toValue: 1, duration: 180, useNativeDriver: true }).start();
      timer.current = setTimeout(hide, VISIBLE_MS);
    },
    [hide, opacity],
  );

  useEffect(() => () => void (timer.current && clearTimeout(timer.current)), []);

  const api: ToastApi = {
    showToast,
    showError: (message) => showToast(message, 'error'),
    showSuccess: (message) => showToast(message, 'success'),
  };

  return (
    <ToastContext.Provider value={api}>
      {children}
      {toast ? <ToastView toast={toast} opacity={opacity} /> : null}
    </ToastContext.Provider>
  );
}

function ToastView({ toast, opacity }: { toast: ToastState; opacity: Animated.Value }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const toneColor =
    toast.tone === 'error'
      ? theme.colors.danger
      : toast.tone === 'success'
        ? theme.colors.success
        : theme.colors.surfaceInverse;

  return (
    <Animated.View
      pointerEvents="none"
      accessibilityLiveRegion="polite"
      accessibilityRole="alert"
      style={[
        styles.container,
        {
          opacity,
          bottom: insets.bottom + theme.spacing.xl,
          backgroundColor: toneColor,
          borderRadius: theme.radius.lg,
          paddingHorizontal: theme.spacing.lg,
          paddingVertical: theme.spacing.md,
        },
        theme.elevation.floating,
      ]}
    >
      <AppText variant="callout" style={{ color: theme.colors.textInverse }}>
        {toast.message}
      </AppText>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  container: {
    position: 'absolute',
    left: 16,
    right: 16,
    alignItems: 'center',
  },
});

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used within a <ToastProvider>.');
  return ctx;
}
