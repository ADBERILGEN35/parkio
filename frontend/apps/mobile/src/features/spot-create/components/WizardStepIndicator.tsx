import { StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui';
import { useLocale } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme';
import {
  WIZARD_DISPLAY_STEPS,
  WIZARD_STEP_LABEL_KEYS,
  wizardStepNumber,
  type WizardDisplayStep,
} from '../lib/wizardSteps';

export interface WizardStepIndicatorProps {
  step: WizardDisplayStep;
}

/**
 * Accessible progress for the 4-step upload wizard.
 * Announces e.g. "Step 2 of 4 · Location".
 */
export function WizardStepIndicator({ step }: WizardStepIndicatorProps) {
  const theme = useTheme();
  const { t } = useLocale();
  const current = wizardStepNumber(step);
  const total = WIZARD_DISPLAY_STEPS.length;
  const label = t(WIZARD_STEP_LABEL_KEYS[step]);
  const progressLabel = t(`Step ${current} of ${total} · ${label}`);

  return (
    <View
      style={styles.wrap}
      accessible
      accessibilityRole="progressbar"
      accessibilityLabel={progressLabel}
      accessibilityValue={{ min: 1, max: total, now: current }}
      testID="wizard-step-indicator"
    >
      <AppText variant="caption" tone="muted">
        {progressLabel}
      </AppText>
      <View style={styles.track} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        {WIZARD_DISPLAY_STEPS.map((key, index) => {
          const active = index < current;
          return (
            <View
              key={key}
              style={[
                styles.segment,
                {
                  backgroundColor: active ? theme.colors.primary : theme.colors.border,
                  borderRadius: theme.radius.full,
                },
              ]}
            />
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: 8 },
  track: { flexDirection: 'row', gap: 6 },
  segment: { flex: 1, height: 4 },
});
