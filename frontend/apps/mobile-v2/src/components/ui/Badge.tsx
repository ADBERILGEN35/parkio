import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from './AppText';
import { radius } from '@/theme/tokens';

export interface BadgeProps {
  label: string;
  icon?: keyof typeof MaterialCommunityIcons.glyphMap;
  /** Foreground color (icon + text). */
  fg: string;
  /** Soft tint background. */
  bg: string;
  size?: 'md' | 'sm';
  style?: StyleProp<ViewStyle>;
}

/** Soft status badge: pill, tinted bg, icon + label — never color alone. */
export function Badge({ label, icon, fg, bg, size = 'md', style }: BadgeProps) {
  return (
    <View
      style={[
        styles.base,
        {
          backgroundColor: bg,
          paddingHorizontal: size === 'sm' ? 8 : 10,
          paddingVertical: size === 'sm' ? 3 : 5,
        },
        style,
      ]}
    >
      {icon && <MaterialCommunityIcons name={icon} size={size === 'sm' ? 12 : 14} color={fg} />}
      <AppText variant={size === 'sm' ? 'labelSm' : 'bodySm'} color={fg} numberOfLines={1}>
        {label}
      </AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  base: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    borderRadius: radius.pill,
    alignSelf: 'flex-start',
  },
});
