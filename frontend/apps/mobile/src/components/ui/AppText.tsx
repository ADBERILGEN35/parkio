import { Text, type TextProps } from 'react-native';
import { useTheme } from '@/theme';
import type { TypographyVariant } from '@/theme';

export interface AppTextProps extends TextProps {
  variant?: TypographyVariant;
  tone?: 'default' | 'muted' | 'inverse' | 'primary' | 'danger' | 'success';
}

/**
 * The single text primitive. Centralises the type scale and color tokens, and
 * keeps `allowFontScaling` on so Dynamic Type / font-size accessibility settings
 * are respected app-wide.
 */
export function AppText({ variant = 'body', tone = 'default', style, ...props }: AppTextProps) {
  const theme = useTheme();
  const typeStyle = theme.typography[variant];
  const color =
    tone === 'muted'
      ? theme.colors.textMuted
      : tone === 'inverse'
        ? theme.colors.textInverse
        : tone === 'primary'
          ? theme.colors.primary
          : tone === 'danger'
            ? theme.colors.danger
            : tone === 'success'
              ? theme.colors.success
              : theme.colors.text;

  return (
    <Text
      allowFontScaling
      style={[
        { fontSize: typeStyle.fontSize, lineHeight: typeStyle.lineHeight, fontWeight: typeStyle.fontWeight, color },
        style,
      ]}
      {...props}
    />
  );
}
