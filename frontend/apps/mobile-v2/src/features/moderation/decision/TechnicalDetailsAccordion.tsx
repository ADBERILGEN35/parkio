import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { PressableScale } from '@/components/ui/PressableScale';
import { useT } from '@/i18n/LocaleProvider';
import { radius } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export type TechnicalDetailRow = {
  label: string;
  value: string;
  mono?: boolean;
};

/**
 * Collapsed-by-default technical evidence disclosure.
 * No shared Accordion primitive exists in mobile-v2 — this follows the same
 * expand/collapse pattern as MapAreaStatusSheet (PressableScale + chevron).
 * Never render prompts or stack traces — callers must filter.
 */
export function TechnicalDetailsAccordion({ rows }: { rows: TechnicalDetailRow[] }) {
  const t = useT();
  const theme = useTheme();
  const { colors } = theme;
  const [open, setOpen] = useState(false);
  const visible = rows.filter((row) => row.value.trim().length > 0);
  if (visible.length === 0) {
    return null;
  }

  return (
    <View
      style={[styles.wrap, { backgroundColor: colors.surfaceContainer1, borderRadius: radius.input }]}
      testID="technical-details"
    >
      <PressableScale
        onPress={() => setOpen((value) => !value)}
        accessibilityRole="button"
        accessibilityState={{ expanded: open }}
        accessibilityLabel={t('decision.technical.title')}
        style={styles.header}
      >
        <View style={styles.headerLabel}>
          <MaterialCommunityIcons name="code-tags" size={16} color={colors.onSurfaceVariant} />
          <AppText variant="labelSm" color={colors.onSurface}>
            {t('decision.technical.title')}
          </AppText>
        </View>
        <MaterialCommunityIcons
          name={open ? 'chevron-up' : 'chevron-down'}
          size={20}
          color={colors.onSurfaceVariant}
        />
      </PressableScale>
      {open ? (
        <View style={[styles.body, { borderTopColor: colors.outlineVariant }]} accessibilityRole="summary">
          {visible.map((row) => (
            <View key={row.label} style={styles.row}>
              <AppText variant="labelSm" color={colors.onSurfaceVariant}>
                {row.label}
              </AppText>
              <AppText
                variant={row.mono ? 'labelSm' : 'bodyMd'}
                color={colors.onSurface}
                style={styles.value}
              >
                {row.value}
              </AppText>
            </View>
          ))}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { overflow: 'hidden' },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    paddingHorizontal: 12,
    paddingVertical: 12,
    minHeight: 44,
  },
  headerLabel: { flexDirection: 'row', alignItems: 'center', gap: 6, flex: 1 },
  body: {
    gap: 10,
    paddingHorizontal: 12,
    paddingBottom: 12,
    paddingTop: 10,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  row: { gap: 2 },
  value: { flexShrink: 1 },
});
