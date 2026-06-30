import { Ionicons } from '@expo/vector-icons';
import { memo } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui';
import { HIT_SLOP, useTheme } from '@/theme';

export interface SearchThisAreaButtonProps {
  visible: boolean;
  loading: boolean;
  topOffset: number;
  onPress: () => void;
}

/**
 * "Search this area" pill — only shown after the map has moved a meaningful
 * distance from the last committed search center, so panning never spams the
 * backend. Centered near the top, below the search bar.
 */
function SearchThisAreaButtonImpl({ visible, loading, topOffset, onPress }: SearchThisAreaButtonProps) {
  const theme = useTheme();
  if (!visible) return null;
  return (
    <View style={[styles.wrap, { top: topOffset }]} pointerEvents="box-none">
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Search this area"
        hitSlop={HIT_SLOP}
        onPress={onPress}
        disabled={loading}
        style={({ pressed }) => [
          styles.pill,
          {
            backgroundColor: pressed ? theme.colors.primaryPressed : theme.colors.primary,
            borderRadius: theme.radius.full,
            ...theme.elevation.floating,
          },
        ]}
      >
        {loading ? (
          <ActivityIndicator color={theme.colors.onPrimary} size="small" />
        ) : (
          <Ionicons name="refresh" size={16} color={theme.colors.onPrimary} />
        )}
        <AppText variant="label" style={{ color: theme.colors.onPrimary }}>
          Search this area
        </AppText>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', left: 0, right: 0, alignItems: 'center' },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingVertical: 10,
    paddingHorizontal: 18,
    minHeight: 44,
  },
});

export const SearchThisAreaButton = memo(SearchThisAreaButtonImpl);
