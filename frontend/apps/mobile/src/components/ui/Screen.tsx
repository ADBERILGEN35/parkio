import type { ReactNode } from 'react';
import { ScrollView, StyleSheet, View, type ViewStyle } from 'react-native';
import { SafeAreaView, type Edge } from 'react-native-safe-area-context';
import { useTheme } from '@/theme';

export interface ScreenProps {
  children: ReactNode;
  /** Wrap content in a ScrollView (default true). Set false for full-bleed screens. */
  scroll?: boolean;
  /** Safe-area edges to pad. Defaults to all but bottom (tab bar owns the bottom). */
  edges?: Edge[];
  contentStyle?: ViewStyle;
  /** Remove the default horizontal/vertical padding. */
  padded?: boolean;
  testID?: string;
}

/**
 * Screen scaffold: safe-area aware, themed background, optional scrolling, and a
 * comfortable default content padding. Every route renders inside a `<Screen>`.
 */
export function Screen({
  children,
  scroll = true,
  edges = ['top', 'left', 'right'],
  contentStyle,
  padded = true,
  testID,
}: ScreenProps) {
  const theme = useTheme();
  // Web container-margin (20px) sides, gutter-sized (16px) vertical rhythm.
  const padding = padded
    ? { paddingHorizontal: theme.spacing.gutter, paddingVertical: theme.spacing.md }
    : undefined;

  return (
    <SafeAreaView testID={testID} style={[styles.flex, { backgroundColor: theme.colors.background }]} edges={edges}>
      {scroll ? (
        <ScrollView
          style={styles.flex}
          contentContainerStyle={[styles.grow, padding, contentStyle]}
          keyboardShouldPersistTaps="always"
          showsVerticalScrollIndicator={false}
        >
          {children}
        </ScrollView>
      ) : (
        <View style={[styles.flex, padding, contentStyle]}>{children}</View>
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  grow: { flexGrow: 1 },
});
