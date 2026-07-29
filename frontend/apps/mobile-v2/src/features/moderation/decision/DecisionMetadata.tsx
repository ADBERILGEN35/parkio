import { StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui/AppText';
import { useTheme } from '@/theme/ThemeProvider';

export type DecisionMetadataItem = {
  label: string;
  value: string;
  mono?: boolean;
};

/** Compact key/value rows for decision metadata (source, code, date, policy). */
export function DecisionMetadata({ items }: { items: DecisionMetadataItem[] }) {
  const theme = useTheme();
  const { colors } = theme;
  const visible = items.filter((item) => item.value.trim().length > 0);
  if (visible.length === 0) {
    return null;
  }

  return (
    <View style={styles.list} testID="decision-metadata" accessibilityRole="summary">
      {visible.map((item) => (
        <View key={item.label} style={styles.row}>
          <AppText variant="labelSm" color={colors.onSurfaceVariant}>
            {item.label}
          </AppText>
          <AppText
            variant={item.mono ? 'labelSm' : 'bodyMd'}
            color={colors.onSurface}
            style={styles.value}
          >
            {item.value}
          </AppText>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  list: { gap: 10 },
  row: { gap: 2 },
  value: { flexShrink: 1 },
});
