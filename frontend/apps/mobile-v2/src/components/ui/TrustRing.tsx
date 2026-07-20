import { View } from 'react-native';
import Svg, { Circle } from 'react-native-svg';
import { AppText } from './AppText';
import { useTheme } from '@/theme/ThemeProvider';

export interface TrustRingProps {
  /** Trust score 0–100. */
  score: number;
  size?: number;
  strokeWidth?: number;
  showScore?: boolean;
}

/** Trust band → semantic color (red / amber / blue / emerald). */
export function trustBandColor(score: number, dark: boolean): string {
  if (score < 25) {
    return dark ? '#FFB4AB' : '#BA1A1A';
  }
  if (score < 50) {
    return dark ? '#FFB955' : '#A06500';
  }
  if (score < 75) {
    return dark ? '#B3C5FF' : '#0050CB';
  }
  return dark ? '#6CF8BB' : '#006C49';
}

export function trustBandOf(score: number): 'UNTRUSTED' | 'LOW_TRUST' | 'MEDIUM_TRUST' | 'HIGH_TRUST' {
  if (score < 25) return 'UNTRUSTED';
  if (score < 50) return 'LOW_TRUST';
  if (score < 75) return 'MEDIUM_TRUST';
  return 'HIGH_TRUST';
}

/** Circular 0–100 trust gauge with the score centered (brief §7.9). */
export function TrustRing({ score, size = 64, strokeWidth = 5, showScore = true }: TrustRingProps) {
  const theme = useTheme();
  const clamped = Math.min(100, Math.max(0, score));
  const color = trustBandColor(clamped, theme.mode === 'dark');
  const r = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * r;

  return (
    <View style={{ width: size, height: size, alignItems: 'center', justifyContent: 'center' }}>
      <Svg width={size} height={size} style={{ position: 'absolute' }}>
        <Circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          stroke={theme.colors.ringTrack}
          strokeWidth={strokeWidth}
          fill="none"
        />
        <Circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          stroke={color}
          strokeWidth={strokeWidth}
          fill="none"
          strokeLinecap="round"
          strokeDasharray={`${circumference} ${circumference}`}
          strokeDashoffset={circumference * (1 - clamped / 100)}
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
        />
      </Svg>
      {showScore && (
        <AppText
          variant={size >= 64 ? 'titleLg' : 'titleMd'}
          tabular
          color={theme.colors.onSurface}
        >
          {Math.round(clamped)}
        </AppText>
      )}
    </View>
  );
}
