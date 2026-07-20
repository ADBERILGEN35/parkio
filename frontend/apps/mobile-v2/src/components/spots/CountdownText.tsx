import { AppText, type TextVariant } from '@/components/ui/AppText';
import { useT } from '@/i18n/LocaleProvider';
import { formatCountdown } from '@/lib/time';
import { freshnessColor } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface CountdownTextProps {
  remainingMs: number;
  /** Remaining-life fraction — drives the color to match the ring. */
  fraction: number;
  variant?: TextVariant;
  /** Append the localized "kaldı / left" suffix. */
  withSuffix?: boolean;
  color?: string;
}

/** Tabular countdown, color-synced with the freshness ring. */
export function CountdownText({
  remainingMs,
  fraction,
  variant = 'bodySm',
  withSuffix,
  color,
}: CountdownTextProps) {
  const theme = useTheme();
  const t = useT();
  const time = formatCountdown(remainingMs);
  const tint = color ?? freshnessColor(fraction, theme);
  return (
    <AppText variant={variant} tabular color={tint} numberOfLines={1}>
      {withSuffix ? t('spot.remaining', { time }) : time}
    </AppText>
  );
}
