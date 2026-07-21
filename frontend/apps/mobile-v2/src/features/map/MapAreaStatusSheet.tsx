import { useMemo, useState } from 'react';
import {
  LayoutAnimation,
  Platform,
  Pressable,
  StyleSheet,
  UIManager,
  useWindowDimensions,
  View,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Glass } from '@/components/ui/Glass';
import { IconButton } from '@/components/ui/IconButton';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

if (Platform.OS === 'android' && UIManager.setLayoutAnimationEnabledExperimental) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

export interface MapAreaStatusSheetProps {
  visible: boolean;
  radiusLabel: string | null;
  level: number | null;
  lastRefreshedAt: Date | null;
  onShare: () => void;
}

/**
 * Collapsible bottom empty-area status (replaces the large centered MapEmptyCard).
 * Session-dismissible; starts collapsed so the map stays mostly visible.
 */
export function MapAreaStatusSheet({
  visible,
  radiusLabel,
  level,
  lastRefreshedAt,
  onShare,
}: MapAreaStatusSheetProps) {
  const theme = useTheme();
  const t = useT();
  const insets = useSafeAreaInsets();
  const { height: windowHeight } = useWindowDimensions();
  const [expanded, setExpanded] = useState(false);
  const [dismissed, setDismissed] = useState(false);

  const maxExpanded = Math.round(windowHeight * 0.42);
  const refreshedLabel = useMemo(() => {
    if (!lastRefreshedAt) return null;
    try {
      return lastRefreshedAt.toLocaleTimeString(undefined, {
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return null;
    }
  }, [lastRefreshedAt]);

  if (!visible || dismissed) {
    return null;
  }

  const animate = () => {
    LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
  };

  const toggle = () => {
    animate();
    setExpanded((value) => !value);
  };

  const dismiss = () => {
    animate();
    setDismissed(true);
  };

  return (
    <View
      style={[styles.host, { bottom: Math.max(insets.bottom, 8) + 8 }]}
      pointerEvents="box-none"
    >
      <Glass
        radius={20}
        contentStyle={[
          styles.panel,
          expanded ? { maxHeight: maxExpanded } : styles.collapsed,
        ]}
      >
        <Pressable
          onPress={toggle}
          accessibilityRole="button"
          accessibilityLabel={expanded ? t('map.empty.collapse') : t('map.empty.expand')}
          style={styles.handleRow}
        >
          <View style={[styles.handle, { backgroundColor: theme.colors.outlineVariant }]} />
        </Pressable>

        <View style={styles.collapsedRow}>
          <View style={[styles.dot, { backgroundColor: theme.colors.tertiary }]} />
          <AppText variant="bodySm" numberOfLines={expanded ? 3 : 2} style={styles.message}>
            {t('map.empty.title')}
          </AppText>
          <IconButton
            icon={expanded ? 'chevron-down' : 'chevron-up'}
            size={36}
            variant="glassless"
            accessibilityLabel={expanded ? t('map.empty.collapse') : t('map.empty.expand')}
            onPress={toggle}
          />
          <IconButton
            icon="close"
            size={36}
            variant="glassless"
            accessibilityLabel={t('common.close')}
            onPress={dismiss}
          />
        </View>

        {!expanded ? (
          <Button
            label={t('map.empty.cta')}
            size="sm"
            block={false}
            style={styles.compactCta}
            onPress={onShare}
          />
        ) : (
          <View style={styles.expandedBody}>
            <AppText variant="titleMd">{t('map.empty.sectionTitle')}</AppText>
            <AppText variant="bodySm" color={theme.colors.onSurfaceVariant}>
              {t('map.empty.body')}
            </AppText>
            {radiusLabel ? (
              <MetaRow
                icon="radar"
                label={t('map.empty.radius')}
                value={radiusLabel}
              />
            ) : null}
            {level != null ? (
              <MetaRow
                icon="stairs"
                label={t('map.empty.level')}
                value={String(level)}
              />
            ) : null}
            {refreshedLabel ? (
              <MetaRow
                icon="clock-outline"
                label={t('map.empty.refreshed')}
                value={refreshedLabel}
              />
            ) : null}
            <Button label={t('map.empty.cta')} size="md" onPress={onShare} />
          </View>
        )}
      </Glass>
    </View>
  );
}

function MetaRow({
  icon,
  label,
  value,
}: {
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  label: string;
  value: string;
}) {
  const theme = useTheme();
  return (
    <View style={styles.metaRow}>
      <MaterialCommunityIcons name={icon} size={16} color={theme.colors.onSurfaceVariant} />
      <AppText variant="labelSm" color={theme.colors.onSurfaceVariant} style={styles.metaLabel}>
        {label}
      </AppText>
      <AppText variant="bodySm" tabular>
        {value}
      </AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  host: {
    position: 'absolute',
    left: 12,
    right: 12,
    zIndex: 4,
  },
  panel: {
    paddingHorizontal: 14,
    paddingTop: 6,
    paddingBottom: 12,
    gap: 8,
  },
  collapsed: {
    minHeight: 88,
    maxHeight: 110,
  },
  handleRow: {
    alignItems: 'center',
    paddingVertical: 2,
  },
  handle: {
    width: 36,
    height: 4,
    borderRadius: 2,
  },
  collapsedRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  message: { flex: 1 },
  compactCta: { alignSelf: 'flex-start', marginLeft: 16 },
  expandedBody: { gap: 10, paddingTop: 4 },
  metaRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  metaLabel: { flex: 1 },
});
