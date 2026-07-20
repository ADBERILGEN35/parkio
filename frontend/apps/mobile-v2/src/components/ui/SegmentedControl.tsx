import { StyleSheet, View } from 'react-native';
import { AppText } from './AppText';
import { PressableScale } from './PressableScale';
import { radius, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface SegmentOption<T extends string> {
  value: T;
  label: string;
  /** Danger segment tints red when selected (legal "Riskli"). */
  tone?: 'default' | 'danger';
}

export interface SegmentedControlProps<T extends string> {
  options: SegmentOption<T>[];
  value: T | null;
  onChange: (value: T) => void;
}

/** Segmented control on the container-2 track; selected segment lifts to surface. */
export function SegmentedControl<T extends string>({
  options,
  value,
  onChange,
}: SegmentedControlProps<T>) {
  const theme = useTheme();
  const { colors } = theme;
  return (
    <View style={[styles.track, { backgroundColor: colors.surfaceContainer2 }]}>
      {options.map((option) => {
        const selected = option.value === value;
        const danger = option.tone === 'danger';
        return (
          <PressableScale
            key={option.value}
            scaleTo={0.97}
            onPress={() => onChange(option.value)}
            accessibilityRole="button"
            accessibilityLabel={option.label}
            accessibilityState={{ selected }}
            style={[
              styles.segment,
              selected
                ? [
                    {
                      backgroundColor: danger ? colors.errorContainer : colors.surface,
                    },
                    theme.mode === 'light' ? shadows.ambientSoft : null,
                  ]
                : null,
            ]}
          >
            <AppText
              variant="bodySm"
              numberOfLines={1}
              color={
                selected
                  ? danger
                    ? colors.error
                    : colors.onSurface
                  : colors.onSurfaceVariant
              }
              style={selected ? styles.selectedLabel : undefined}
            >
              {option.label}
            </AppText>
          </PressableScale>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  track: {
    flexDirection: 'row',
    borderRadius: radius.input + 2,
    padding: 3,
    gap: 3,
  },
  segment: {
    flex: 1,
    borderRadius: radius.input,
    paddingVertical: 9,
    alignItems: 'center',
    justifyContent: 'center',
  },
  selectedLabel: { fontFamily: 'Inter_600SemiBold' },
});
