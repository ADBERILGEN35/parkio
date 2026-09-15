import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from './AppText';
import { PressableScale } from './PressableScale';
import { radius } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface ChipProps {
  label: string;
  icon?: keyof typeof MaterialCommunityIcons.glyphMap;
  /** Selectable chips flip to the primary-fixed selected style. */
  selected?: boolean;
  onPress?: () => void;
  disabled?: boolean;
  size?: 'md' | 'sm';
  /** Label line clamp — raise for long municipal source labels. */
  numberOfLines?: number;
  style?: StyleProp<ViewStyle>;
}

/**
 * Attribute / filter chip: pill on surface-container-1; selected =
 * primary-fixed fill + primary ink (FILL-1 feel per the pen kit).
 */
export function Chip({
  label,
  icon,
  selected,
  onPress,
  disabled,
  size = 'md',
  numberOfLines = 1,
  style,
}: ChipProps) {
  const theme = useTheme();
  const { colors } = theme;
  const fg = selected
    ? theme.mode === 'dark'
      ? colors.primaryFixedDim
      : colors.primary
    : colors.onSurfaceVariant;
  const bg = selected ? colors.primaryFixed : colors.surfaceContainer1;

  const content = (
    <View
      style={[
        styles.inner,
        {
          paddingHorizontal: size === 'sm' ? 10 : 12,
          paddingVertical: size === 'sm' ? 5 : 7,
          backgroundColor: bg,
          borderRadius: radius.pill,
          opacity: disabled ? 0.45 : 1,
        },
      ]}
    >
      {icon && <MaterialCommunityIcons name={icon} size={size === 'sm' ? 13 : 15} color={fg} />}
      <AppText variant={size === 'sm' ? 'labelSm' : 'bodySm'} color={fg} numberOfLines={numberOfLines}>
        {label}
      </AppText>
    </View>
  );

  if (!onPress) {
    return <View style={style}>{content}</View>;
  }

  return (
    <PressableScale
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ selected: Boolean(selected), disabled: Boolean(disabled) }}
      style={style}
    >
      {content}
    </PressableScale>
  );
}

const styles = StyleSheet.create({
  inner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    alignSelf: 'flex-start',
  },
});
