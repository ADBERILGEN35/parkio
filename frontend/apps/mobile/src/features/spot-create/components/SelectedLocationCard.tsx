import { Ionicons } from '@expo/vector-icons';
import { memo } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui';
import { useTheme } from '@/theme';
import type { SelectedPlace } from '../hooks/useSelectedPlace';

export interface SelectedLocationCardProps {
  place: SelectedPlace | null;
  isResolving: boolean;
  /** No place could be resolved for the pin — show a neutral "dropped pin" row. */
  isUnresolved: boolean;
}

/**
 * Human-readable "where the pin is" row — never raw coordinates. Shows the
 * reverse-resolved street + area while the pin rests, a subtle resolving state
 * while it settles, and a neutral fallback when the address lookup fails.
 */
function SelectedLocationCardImpl({ place, isResolving, isUnresolved }: SelectedLocationCardProps) {
  const theme = useTheme();

  const primary = place?.primary ?? (isUnresolved ? 'Dropped pin' : 'Finding address…');
  const secondary = place?.secondary || (isUnresolved ? 'Move the map to fine-tune the exact spot.' : undefined);

  return (
    <View style={styles.row} accessible accessibilityLabel={`Selected area: ${primary}${secondary ? `, ${secondary}` : ''}`}>
      <View style={[styles.iconDisc, { backgroundColor: theme.colors.primarySoft, borderRadius: theme.radius.full }]}>
        <Ionicons name="location-outline" size={18} color={theme.colors.primary} />
      </View>
      <View style={styles.textCol}>
        <AppText variant="caption" tone="muted">
          Selected area
        </AppText>
        <AppText variant="subtitle" numberOfLines={1}>
          {primary}
        </AppText>
        {secondary ? (
          <AppText variant="caption" tone="muted" numberOfLines={1}>
            {secondary}
          </AppText>
        ) : null}
      </View>
      {isResolving && place ? <ActivityIndicator size="small" color={theme.colors.textMuted} /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  iconDisc: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
  textCol: { flex: 1, gap: 1 },
});

export const SelectedLocationCard = memo(SelectedLocationCardImpl);
