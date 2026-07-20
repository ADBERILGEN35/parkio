import { Text, type TextProps, type TextStyle } from 'react-native';
import { typeScale } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export type TextVariant = keyof typeof typeScale;

export interface AppTextProps extends TextProps {
  variant?: TextVariant;
  /** Semantic color; defaults to the primary ink. */
  color?: string;
  /** Tabular figures — REQUIRED for countdowns and aligned numbers. */
  tabular?: boolean;
  /** Uppercase label styling helper. */
  uppercase?: boolean;
  align?: TextStyle['textAlign'];
}

/**
 * The only Text in the app. Guarantees Inter, the type scale, theme ink and
 * tabular numerals where time is typography (brief §5.6).
 */
export function AppText({
  variant = 'bodyMd',
  color,
  tabular,
  uppercase,
  align,
  style,
  children,
  ...rest
}: AppTextProps) {
  const theme = useTheme();
  const base = typeScale[variant];
  return (
    <Text
      {...rest}
      style={[
        {
          fontFamily: base.fontFamily,
          fontSize: base.fontSize,
          lineHeight: base.lineHeight,
          letterSpacing: base.letterSpacing,
          color: color ?? theme.colors.onSurface,
        },
        tabular ? { fontVariant: ['tabular-nums'] } : null,
        uppercase ? { textTransform: 'uppercase' } : null,
        align ? { textAlign: align } : null,
        style,
      ]}
    >
      {children}
    </Text>
  );
}
