import type { ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from './AppText';
import { PressableScale } from './PressableScale';
import { useTheme } from '@/theme/ThemeProvider';

export interface ListRowProps {
  icon?: keyof typeof MaterialCommunityIcons.glyphMap;
  label: string;
  sublabel?: string;
  trailing?: ReactNode;
  onPress?: () => void;
  showChevron?: boolean;
  danger?: boolean;
}

/** Settings/menu row: icon bubble, label, trailing value/chevron. */
export function ListRow({
  icon,
  label,
  sublabel,
  trailing,
  onPress,
  showChevron = Boolean(onPress),
  danger,
}: ListRowProps) {
  const theme = useTheme();
  const { colors } = theme;
  const fg = danger ? colors.error : colors.onSurface;

  const content = (
    <View style={styles.row}>
      {icon && (
        <View style={[styles.iconBubble, { backgroundColor: danger ? colors.errorContainer : colors.surfaceContainer1 }]}>
          <MaterialCommunityIcons name={icon} size={18} color={danger ? colors.error : colors.primary} />
        </View>
      )}
      <View style={styles.labels}>
        <AppText variant="bodyLg" color={fg} numberOfLines={1}>
          {label}
        </AppText>
        {sublabel ? (
          <AppText variant="bodySm" color={colors.onSurfaceVariant} numberOfLines={1}>
            {sublabel}
          </AppText>
        ) : null}
      </View>
      {trailing}
      {showChevron && (
        <MaterialCommunityIcons name="chevron-right" size={20} color={colors.outline} />
      )}
    </View>
  );

  if (!onPress) {
    return content;
  }
  return (
    <PressableScale scaleTo={0.98} onPress={onPress} accessibilityRole="button" accessibilityLabel={label}>
      {content}
    </PressableScale>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 12,
    minHeight: 52,
  },
  iconBubble: {
    width: 34,
    height: 34,
    borderRadius: 17,
    alignItems: 'center',
    justifyContent: 'center',
  },
  labels: { flex: 1, gap: 1 },
});
