import { StyleSheet, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { useTheme } from '@/theme/ThemeProvider';

export interface LevelProgressBarProps {
  /** Progress fraction in [0,1] toward the next level. */
  fraction: number;
  height?: number;
}

/** Horizontal level progress: #0050CB → #0066FF gradient on the fixed-dim track. */
export function LevelProgressBar({ fraction, height = 8 }: LevelProgressBarProps) {
  const theme = useTheme();
  const clamped = Math.min(1, Math.max(0, fraction));
  return (
    <View
      style={[
        styles.track,
        { height, borderRadius: height / 2, backgroundColor: theme.colors.ringTrack },
      ]}
      accessibilityRole="progressbar"
      accessibilityValue={{ min: 0, max: 100, now: Math.round(clamped * 100) }}
    >
      <LinearGradient
        colors={['#0050CB', '#0066FF']}
        start={{ x: 0, y: 0.5 }}
        end={{ x: 1, y: 0.5 }}
        style={{
          width: `${Math.max(clamped * 100, clamped > 0 ? 4 : 0)}%`,
          height,
          borderRadius: height / 2,
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  track: { width: '100%', overflow: 'hidden' },
});
