import { Ionicons } from '@expo/vector-icons';
import { memo } from 'react';
import { StyleSheet, View } from 'react-native';
import { AppText, Button } from '@/components/ui';
import { useTheme } from '@/theme';
import type { LocationPermission } from '../hooks/useLocation';

export interface LocationPermissionCardProps {
  permission: LocationPermission;
  loading: boolean;
  bottomOffset: number;
  onEnable: () => void;
  onOpenSettings: () => void;
}

const COPY: Record<
  Exclude<LocationPermission, 'granted' | 'prompting'>,
  { title: string; body: string; cta: string }
> = {
  undetermined: {
    title: 'See parking near you',
    body: 'Turn on location to center the map on where you are and find the closest spots.',
    cta: 'Enable location',
  },
  denied: {
    title: 'Location is off',
    body: 'We can’t show spots near you without location access. You can still search any area.',
    cta: 'Try again',
  },
  blocked: {
    title: 'Location is blocked',
    body: 'Location is turned off for Parkio in system settings. Enable it to use your position.',
    cta: 'Open settings',
  },
};

/**
 * Bottom card surfacing the location-permission state without blocking the map
 * (the user can always browse/search the default city). Covers first-launch,
 * denied-but-retryable, and permanently-blocked (Settings) paths.
 */
function LocationPermissionCardImpl({
  permission,
  loading,
  bottomOffset,
  onEnable,
  onOpenSettings,
}: LocationPermissionCardProps) {
  const theme = useTheme();
  if (permission === 'granted' || permission === 'prompting') return null;
  const copy = COPY[permission];
  const isBlocked = permission === 'blocked';

  return (
    <View style={[styles.wrap, { bottom: bottomOffset }]} pointerEvents="box-none">
      <View
        accessibilityRole="summary"
        style={[
          styles.card,
          {
            backgroundColor: theme.colors.surface,
            borderColor: theme.colors.border,
            borderRadius: theme.radius.xl,
            ...theme.elevation.floating,
          },
        ]}
      >
        <View style={styles.header}>
          <Ionicons name="location-outline" size={22} color={theme.colors.primary} />
          <AppText variant="subtitle" style={styles.flex}>
            {copy.title}
          </AppText>
        </View>
        <AppText variant="body" tone="muted">
          {copy.body}
        </AppText>
        <Button
          label={copy.cta}
          testID="map.enableLocation"
          loading={loading}
          onPress={isBlocked ? onOpenSettings : onEnable}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', left: 12, right: 12 },
  card: { padding: 16, gap: 12, borderWidth: 1 },
  header: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  flex: { flex: 1 },
});

export const LocationPermissionCard = memo(LocationPermissionCardImpl);
