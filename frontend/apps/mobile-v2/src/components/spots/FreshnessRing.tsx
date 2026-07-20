import { useEffect, useState } from 'react';
import { View } from 'react-native';
import Svg, { Circle } from 'react-native-svg';
import { freshnessColor } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface FreshnessRingProps {
  /** Remaining-life fraction in [0,1]; null renders the indeterminate state. */
  fraction: number | null;
  size?: 28 | 48 | 64 | number;
  strokeWidth?: number;
  /** Center content (photo thumb, icon, countdown digits). */
  children?: React.ReactNode;
  /** Overrides the fraction-derived color (e.g. slate for pending states). */
  color?: string;
  trackColor?: string;
}

/**
 * THE signature element (brief §5.1): a thin ring that depletes clockwise with
 * the spot's remaining life. Blue >66%, amber 33–66%, red <33%. Always paired
 * with a numeric countdown at the call site — the ring is never the only
 * indicator.
 */
export function FreshnessRing({
  fraction,
  size = 48,
  strokeWidth = 2.5,
  children,
  color,
  trackColor,
}: FreshnessRingProps) {
  const theme = useTheme();
  const clamped = fraction === null ? 0.75 : Math.min(1, Math.max(0, fraction));
  const ringColor =
    color ?? (fraction === null ? theme.colors.outline : freshnessColor(clamped, theme));
  const r = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * r;
  const dashOffset = circumference * (1 - clamped);

  return (
    <View
      style={{ width: size, height: size, alignItems: 'center', justifyContent: 'center' }}
      accessibilityElementsHidden
    >
      <Svg width={size} height={size} style={{ position: 'absolute' }}>
        <Circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          stroke={trackColor ?? theme.colors.ringTrack}
          strokeWidth={strokeWidth}
          fill="none"
        />
        <Circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          stroke={ringColor}
          strokeWidth={strokeWidth}
          fill="none"
          strokeLinecap="round"
          strokeDasharray={`${circumference} ${circumference}`}
          strokeDashoffset={dashOffset}
          // Depletes clockwise from 12 o'clock.
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
        />
      </Svg>
      {children}
    </View>
  );
}

/** Second-resolution shared clock for countdown surfaces. */
export function useNowTick(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}
