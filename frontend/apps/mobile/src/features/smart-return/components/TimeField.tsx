import { Ionicons } from '@expo/vector-icons';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui';
import { HIT_SLOP, MIN_TOUCH_TARGET, useTheme } from '@/theme';
import { stepTime } from '../lib/time';

export interface TimeFieldProps {
  label: string;
  /** HH:mm (24h). */
  value: string;
  onChange: (next: string) => void;
  disabled?: boolean;
  /** Prefix for the stepper testIDs, e.g. `smartReturn.today.time`. */
  testIDPrefix?: string;
}

const MINUTE_STEP = 5;

/**
 * Native stand-in for the web's `<input type="time">`: an hour and a minute
 * column, each with chevron steppers (±1h / ±5m, wrapping). Keeps the picked
 * value glanceable in the web's title type size.
 */
export function TimeField({ label, value, onChange, disabled = false, testIDPrefix }: TimeFieldProps) {
  const theme = useTheme();
  const [hours = '18', minutes = '30'] = value.split(':');

  return (
    <View style={styles.group}>
      <AppText variant="caption" tone="muted">
        {label}
      </AppText>
      <View
        accessibilityLabel={`${label}: ${value}`}
        style={[
          styles.picker,
          {
            backgroundColor: theme.colors.surface,
            borderColor: theme.colors.border,
            borderRadius: theme.radius.xl,
          },
        ]}
      >
        <Column
          unitLabel="hour"
          display={hours}
          disabled={disabled}
          testIDPrefix={testIDPrefix ? `${testIDPrefix}.hour` : undefined}
          onStep={(delta) => onChange(stepTime(value, 'hour', delta))}
        />
        <AppText variant="title" tone="muted">
          :
        </AppText>
        <Column
          unitLabel="minute"
          display={minutes}
          disabled={disabled}
          testIDPrefix={testIDPrefix ? `${testIDPrefix}.minute` : undefined}
          onStep={(delta) => onChange(stepTime(value, 'minute', delta * MINUTE_STEP))}
        />
      </View>
    </View>
  );
}

function Column({
  unitLabel,
  display,
  disabled,
  onStep,
  testIDPrefix,
}: {
  unitLabel: 'hour' | 'minute';
  display: string;
  disabled: boolean;
  onStep: (delta: 1 | -1) => void;
  testIDPrefix?: string;
}) {
  const theme = useTheme();
  return (
    <View style={styles.column}>
      <Stepper
        icon="chevron-up"
        accessibilityLabel={`Increase ${unitLabel}`}
        disabled={disabled}
        testID={testIDPrefix ? `${testIDPrefix}.up` : undefined}
        onPress={() => onStep(1)}
      />
      <AppText variant="title" style={disabled ? { color: theme.colors.textMuted } : null}>
        {display}
      </AppText>
      <Stepper
        icon="chevron-down"
        accessibilityLabel={`Decrease ${unitLabel}`}
        disabled={disabled}
        testID={testIDPrefix ? `${testIDPrefix}.down` : undefined}
        onPress={() => onStep(-1)}
      />
    </View>
  );
}

function Stepper({
  icon,
  accessibilityLabel,
  disabled,
  onPress,
  testID,
}: {
  icon: 'chevron-up' | 'chevron-down';
  accessibilityLabel: string;
  disabled: boolean;
  onPress: () => void;
  testID?: string;
}) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ disabled }}
      disabled={disabled}
      hitSlop={HIT_SLOP}
      testID={testID}
      onPress={onPress}
      style={({ pressed }) => [
        styles.stepper,
        {
          borderRadius: theme.radius.full,
          backgroundColor: pressed ? theme.colors.surfaceMuted : 'transparent',
          opacity: disabled ? 0.4 : 1,
        },
      ]}
    >
      <Ionicons name={icon} size={20} color={theme.colors.primary} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  group: { gap: 6 },
  picker: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
    borderWidth: 1,
    paddingVertical: 8,
  },
  column: { alignItems: 'center', gap: 2 },
  stepper: {
    minWidth: MIN_TOUCH_TARGET,
    height: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
