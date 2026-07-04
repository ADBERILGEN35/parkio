import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, TextInput, View } from 'react-native';
import type { GeocodeResult } from '@parkio/types';
import { AppText } from '@/components/ui';
import { usePlaceSearch } from '@/features/map/hooks/usePlaceSearch';
import { HIT_SLOP, MIN_TOUCH_TARGET, useTheme } from '@/theme';

export interface HomeAreaFieldProps {
  hasHome: boolean;
  /** Saved display label; never coordinates. */
  label: string;
  error?: string;
  disabled?: boolean;
  onSelect: (place: GeocodeResult) => void;
  onRemove: () => void;
}

/**
 * Web `HomeLocationField`, translated: a saved-home chip (icon disc, label,
 * "Saved" check, Change/Remove) that flips into an inline geocoding search.
 * Only the human-readable label is ever rendered — coordinates stay in state.
 */
export function HomeAreaField({ hasHome, label, error, disabled = false, onSelect, onRemove }: HomeAreaFieldProps) {
  const theme = useTheme();
  // Collapsing back to the saved chip happens in the select handler below, so
  // no effect is needed to sync `editing` with `hasHome`.
  const [editing, setEditing] = useState(!hasHome);

  if (hasHome && !editing) {
    return (
      <View style={styles.group}>
        <AppText variant="caption" tone="muted">
          Home area
        </AppText>
        <View
          style={[
            styles.chip,
            {
              backgroundColor: theme.colors.surface,
              borderColor: theme.colors.border,
              borderRadius: theme.radius.xl,
            },
          ]}
        >
          <View
            style={[styles.disc, { backgroundColor: theme.colors.primarySoft, borderRadius: theme.radius.full }]}
          >
            <Ionicons name="home" size={18} color={theme.colors.primary} />
          </View>
          <View style={styles.chipText}>
            <AppText variant="body" numberOfLines={1} style={styles.chipLabel}>
              {label || 'Saved area'}
            </AppText>
            <View style={styles.savedRow}>
              <Ionicons name="checkmark" size={13} color={theme.colors.success} />
              <AppText variant="caption" tone="success">
                Saved
              </AppText>
            </View>
          </View>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Change home area"
            disabled={disabled}
            hitSlop={HIT_SLOP}
            testID="smartReturn.home.change"
            onPress={() => setEditing(true)}
          >
            <AppText variant="label" tone="primary">
              Change
            </AppText>
          </Pressable>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Remove home area"
            disabled={disabled}
            hitSlop={HIT_SLOP}
            testID="smartReturn.home.remove"
            onPress={onRemove}
            style={styles.remove}
          >
            <Ionicons name="close" size={18} color={theme.colors.textMuted} />
          </Pressable>
        </View>
      </View>
    );
  }

  return (
    <HomeAreaSearch
      disabled={disabled}
      error={error}
      onSelect={(place) => {
        onSelect(place);
        setEditing(false);
      }}
    />
  );
}

function HomeAreaSearch({
  disabled,
  error,
  onSelect,
}: {
  disabled: boolean;
  error?: string;
  onSelect: (place: GeocodeResult) => void;
}) {
  const theme = useTheme();
  const [query, setQuery] = useState('');
  const [focused, setFocused] = useState(false);
  const { results, isSearching, isError, isActive } = usePlaceSearch(query);

  return (
    <View style={styles.group}>
      <AppText variant="caption" tone="muted">
        Home area
      </AppText>
      <View
        style={[
          styles.searchBar,
          {
            backgroundColor: theme.colors.surface,
            borderColor: focused ? theme.colors.primary : error ? theme.colors.danger : theme.colors.border,
            borderRadius: theme.radius.md,
          },
        ]}
      >
        <Ionicons name="search" size={18} color={theme.colors.textMuted} />
        <TextInput
          testID="smartReturn.home.search"
          accessibilityLabel="Search your street or neighbourhood"
          value={query}
          editable={!disabled}
          onChangeText={setQuery}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          placeholder="Search your street or neighbourhood"
          placeholderTextColor={theme.colors.textMuted}
          returnKeyType="search"
          autoCorrect={false}
          style={[styles.input, { color: theme.colors.text }]}
        />
        {isSearching ? <ActivityIndicator size="small" color={theme.colors.primary} /> : null}
      </View>

      {isActive ? (
        <View
          style={[
            styles.results,
            { backgroundColor: theme.colors.surface, borderColor: theme.colors.border, borderRadius: theme.radius.lg },
          ]}
        >
          {isError ? (
            <ResultRow icon="warning-outline" primary="Search is unavailable" secondary="Please try again." muted />
          ) : results.length === 0 && !isSearching ? (
            <ResultRow icon="information-circle-outline" primary="No places found" muted />
          ) : (
            results.map((place) => (
              <ResultRow
                key={place.id}
                testID={`smartReturn.home.result.${place.id}`}
                icon="location-outline"
                primary={place.primary}
                secondary={place.secondary}
                onPress={() => onSelect(place)}
              />
            ))
          )}
        </View>
      ) : null}

      {error ? (
        <AppText variant="caption" tone="danger" accessibilityRole="alert">
          {error}
        </AppText>
      ) : null}
    </View>
  );
}

function ResultRow({
  icon,
  primary,
  secondary,
  onPress,
  muted,
  testID,
}: {
  icon: 'location-outline' | 'warning-outline' | 'information-circle-outline';
  primary: string;
  secondary?: string;
  onPress?: () => void;
  muted?: boolean;
  testID?: string;
}) {
  const theme = useTheme();
  return (
    <Pressable
      testID={testID}
      accessibilityRole={onPress ? 'button' : 'text'}
      accessibilityLabel={secondary ? `${primary}, ${secondary}` : primary}
      disabled={!onPress}
      onPress={onPress}
      style={({ pressed }) => [styles.row, { backgroundColor: pressed ? theme.colors.surfaceMuted : 'transparent' }]}
    >
      <Ionicons name={icon} size={18} color={muted ? theme.colors.textMuted : theme.colors.primary} />
      <View style={styles.rowText}>
        <AppText variant="body" numberOfLines={1}>
          {primary}
        </AppText>
        {secondary ? (
          <AppText variant="caption" tone="muted" numberOfLines={1}>
            {secondary}
          </AppText>
        ) : null}
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  group: { gap: 6 },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    borderWidth: 1,
    padding: 10,
  },
  disc: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
  chipText: { flex: 1, minWidth: 0 },
  chipLabel: { fontWeight: '500' },
  savedRow: { flexDirection: 'row', alignItems: 'center', gap: 3 },
  remove: { padding: 4 },
  searchBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    borderWidth: 1,
    paddingHorizontal: 12,
    minHeight: MIN_TOUCH_TARGET,
  },
  input: { flex: 1, fontSize: 15, paddingVertical: 0 },
  results: { borderWidth: 1, overflow: 'hidden' },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 12,
    paddingVertical: 10,
    minHeight: MIN_TOUCH_TARGET,
  },
  rowText: { flex: 1 },
});
