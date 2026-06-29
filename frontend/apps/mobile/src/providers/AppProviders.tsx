import type { ReactNode } from 'react';
import { StyleSheet } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { ErrorBoundary } from '@/components/feedback/ErrorBoundary';
import { ThemeProvider } from '@/theme';
import { QueryProvider } from './QueryProvider';
import { ToastProvider } from './ToastProvider';

/**
 * Single composition root for every cross-cutting provider, in dependency order:
 *
 *   ErrorBoundary            (catches everything below)
 *     GestureHandlerRootView (required by gesture/navigation libs)
 *       SafeAreaProvider     (insets for Screen/Toast/Banner)
 *         ThemeProvider      (colors/spacing/type)
 *           QueryProvider    (server state)
 *             ToastProvider  (global feedback)
 */
export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <ErrorBoundary>
      <GestureHandlerRootView style={styles.flex}>
        <SafeAreaProvider>
          <ThemeProvider>
            <QueryProvider>
              <ToastProvider>{children}</ToastProvider>
            </QueryProvider>
          </ThemeProvider>
        </SafeAreaProvider>
      </GestureHandlerRootView>
    </ErrorBoundary>
  );
}

const styles = StyleSheet.create({ flex: { flex: 1 } });
