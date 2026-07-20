import { StyleSheet, View } from 'react-native';
import { AppText } from './AppText';
import { useTheme } from '@/theme/ThemeProvider';

export interface WizardProgressProps {
  steps: string[];
  /** Zero-based active index. */
  activeIndex: number;
}

/** 4-segment wizard progress with labels (brief §7.14). */
export function WizardProgress({ steps, activeIndex }: WizardProgressProps) {
  const theme = useTheme();
  const { colors } = theme;
  return (
    <View style={styles.container}>
      <View style={styles.bars}>
        {steps.map((step, index) => (
          <View
            key={step}
            style={[
              styles.bar,
              {
                backgroundColor: index <= activeIndex ? colors.primary : colors.ringTrack,
              },
            ]}
          />
        ))}
      </View>
      <View style={styles.labels}>
        {steps.map((step, index) => (
          <AppText
            key={step}
            variant="labelSm"
            numberOfLines={1}
            color={index === activeIndex ? colors.primary : colors.onSurfaceVariant}
            style={[styles.label, index === activeIndex ? styles.activeLabel : null]}
          >
            {step}
          </AppText>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: 6 },
  bars: { flexDirection: 'row', gap: 6 },
  bar: { flex: 1, height: 4, borderRadius: 2 },
  labels: { flexDirection: 'row', gap: 6 },
  label: { flex: 1, textAlign: 'center' },
  activeLabel: { fontFamily: 'Inter_600SemiBold' },
});
