import type { ReactNode } from 'react';
import { Image, StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { PublicSpot } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Chip } from '@/components/ui/Chip';
import { Glass } from '@/components/ui/Glass';
import { PressableScale } from '@/components/ui/PressableScale';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { formatDistance, formatSharedAgo, remainingFraction, remainingMs } from '@/lib/time';
import { radius, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';
import { CountdownText } from './CountdownText';
import { FreshnessRing, useNowTick } from './FreshnessRing';
import { isLiveStatus, statusVisual } from './statusVisuals';
import { spotChips, spotTitle } from './spotChips';

export interface SpotCardProps {
  spot: PublicSpot;
  /** Resolved signed photo URL (or local uri for previews). */
  photoUri: string | null | undefined;
  distanceMeters?: number | null;
  onPress?: () => void;
  /** Owner-view extra line (verification counts etc.). */
  footer?: ReactNode;
  /** Preview mode (share review): hides the live countdown machinery. */
  preview?: boolean;
}

/**
 * The evidence stack (brief §5.5): photo → freshness → status → attributes.
 * Photo header with a glass status badge (top-left) and the Freshness Ring +
 * countdown (top-right); title, freshness line, chips.
 */
export function SpotCard({
  spot,
  photoUri,
  distanceMeters,
  onPress,
  footer,
  preview,
}: SpotCardProps) {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const now = useNowTick(preview ? 60_000 : 1000);
  const { colors } = theme;

  const live = isLiveStatus(spot.status);
  const fraction = live ? remainingFraction(spot.createdAt, spot.expiresAt, now) : 0;
  const remaining = live ? remainingMs(spot.expiresAt, now) : 0;
  const visual = statusVisual(spot.status, theme);
  const chips = spotChips(spot, t);

  const body = (
    <View
      style={[
        styles.card,
        { backgroundColor: colors.surface, borderRadius: radius.card },
        theme.mode === 'light' ? shadows.ambientSoft : null,
      ]}
    >
      <View style={styles.photoWrap}>
        {photoUri ? (
          <Image source={{ uri: photoUri }} style={styles.photo} resizeMode="cover" />
        ) : (
          <View style={[styles.photo, styles.photoFallback, { backgroundColor: colors.surfaceContainer3 }]}>
            <MaterialCommunityIcons name="image-outline" size={28} color={colors.outline} />
          </View>
        )}
        {(spot.status === 'FILLED' || spot.status === 'EXPIRED') && (
          <View style={[styles.photo, styles.photoDim, { backgroundColor: colors.scrim }]} />
        )}
        <Glass radius={999} style={styles.statusBadge} contentStyle={styles.statusBadgeInner}>
          <MaterialCommunityIcons name={visual.icon} size={13} color={visual.fg} />
          <AppText variant="labelSm" color={visual.fg} numberOfLines={1}>
            {t(`status.${spot.status}`)}
          </AppText>
        </Glass>
        {live && !preview ? (
          <Glass radius={999} style={styles.ringBadge} contentStyle={styles.ringBadgeInner}>
            <FreshnessRing fraction={fraction} size={28} strokeWidth={2.5} />
            <CountdownText remainingMs={remaining} fraction={fraction} variant="bodySm" />
          </Glass>
        ) : null}
      </View>

      <View style={styles.body}>
        <AppText variant="titleMd" numberOfLines={1}>
          {spotTitle(spot, t)}
        </AppText>
        <View style={styles.metaRow}>
          <AppText variant="bodySm" color={colors.onSurfaceVariant} numberOfLines={1} style={styles.metaText}>
            {preview ? t('spot.justShared') : formatSharedAgo(spot.createdAt, now, locale, t)}
            {typeof distanceMeters === 'number'
              ? ` · ${formatDistance(distanceMeters, locale)}`
              : ''}
          </AppText>
        </View>
        <View style={styles.chips}>
          {chips.map((chip) => (
            <Chip key={chip.key} icon={chip.icon} label={chip.label} size="sm" />
          ))}
        </View>
        {footer}
      </View>
    </View>
  );

  if (!onPress) {
    return body;
  }
  return (
    <PressableScale scaleTo={0.98} onPress={onPress} accessibilityRole="button" accessibilityLabel={spotTitle(spot, t)}>
      {body}
    </PressableScale>
  );
}

const styles = StyleSheet.create({
  card: { overflow: 'hidden' },
  photoWrap: { height: 148 },
  photo: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 },
  photoFallback: { alignItems: 'center', justifyContent: 'center' },
  photoDim: { opacity: 0.45 },
  statusBadge: { position: 'absolute', top: 10, left: 10 },
  statusBadgeInner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: 9,
    paddingVertical: 5,
  },
  ringBadge: { position: 'absolute', top: 8, right: 8 },
  ringBadgeInner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingLeft: 4,
    paddingRight: 9,
    paddingVertical: 4,
  },
  body: { padding: 12, gap: 6 },
  metaRow: { flexDirection: 'row', alignItems: 'center' },
  metaText: { flexShrink: 1 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
});
