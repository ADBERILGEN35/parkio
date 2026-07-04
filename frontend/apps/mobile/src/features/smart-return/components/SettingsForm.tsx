import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';
import type { SmartReturnSettings } from '@parkio/types';
import { smartReturnSettingsSchema } from '@parkio/validation';
import { AppText, Button } from '@/components/ui';
import { useToast } from '@/providers/ToastProvider';
import { HIT_SLOP, MIN_TOUCH_TARGET, useTheme } from '@/theme';
import { useSmartReturnMutations } from '../hooks/useSmartReturnSettings';
import { FALLBACK_RETURN_TIME } from '../lib/time';
import { HomeAreaField } from './HomeAreaField';
import { TimeField } from './TimeField';

export interface SettingsFormProps {
  settings: SmartReturnSettings;
  submitLabel: string;
  /** Show the "Turn off Smart Return" escape hatch (settings mode only). */
  allowTurnOff?: boolean;
}

interface FieldErrors {
  home?: string;
  leadMinutes?: string;
}

/**
 * Web `SmartReturnSettingsForm`, translated: home area picker, usual return
 * time, and a collapsed "Advanced" lead-minutes field. Validates with the
 * shared `smartReturnSettingsSchema`; every save sends `enabled: true` (the
 * form only exists to opt in or tune), while "Turn off" sends `enabled: false`.
 */
export function SettingsForm({ settings, submitLabel, allowTurnOff = false }: SettingsFormProps) {
  const theme = useTheme();
  const toast = useToast();
  const { saveSettings } = useSmartReturnMutations();

  const [homeLatitude, setHomeLatitude] = useState(settings.homeLatitude);
  const [homeLongitude, setHomeLongitude] = useState(settings.homeLongitude);
  const [homeLabel, setHomeLabel] = useState(settings.homeLabel ?? '');
  const [defaultReturnTime, setDefaultReturnTime] = useState(settings.defaultReturnTime ?? FALLBACK_RETURN_TIME);
  const [leadMinutes, setLeadMinutes] = useState(String(settings.reminderLeadMinutes));
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [errors, setErrors] = useState<FieldErrors>({});

  const pending = saveSettings.isPending;
  const hasHome = homeLatitude !== null && homeLongitude !== null;

  const submit = () => {
    const parsed = smartReturnSettingsSchema.safeParse({
      enabled: true,
      homeLatitude,
      homeLongitude,
      homeLabel,
      defaultReturnTime,
      reminderLeadMinutes: leadMinutes,
    });
    if (!parsed.success) {
      const next: FieldErrors = {};
      for (const issue of parsed.error.issues) {
        if (issue.path[0] === 'homeLatitude' || issue.path[0] === 'homeLongitude') {
          next.home = issue.message;
        }
        if (issue.path[0] === 'reminderLeadMinutes') next.leadMinutes = issue.message;
      }
      // Lead-minute problems live behind the collapsed Advanced toggle — open it.
      if (next.leadMinutes) setAdvancedOpen(true);
      setErrors(next);
      return;
    }
    setErrors({});
    saveSettings.mutate(
      {
        enabled: true,
        homeLatitude: parsed.data.homeLatitude,
        homeLongitude: parsed.data.homeLongitude,
        homeLabel: parsed.data.homeLabel || null,
        defaultReturnTime: parsed.data.defaultReturnTime,
        reminderLeadMinutes: parsed.data.reminderLeadMinutes,
      },
      {
        onSuccess: () => toast.showSuccess('Smart Return saved.'),
        onError: () => toast.showError('We could not save your settings. Please try again.'),
      },
    );
  };

  const turnOff = () =>
    saveSettings.mutate(
      { enabled: false },
      {
        onSuccess: () => toast.showSuccess('Smart Return is off.'),
        onError: () => toast.showError('We could not save your settings. Please try again.'),
      },
    );

  return (
    <View style={styles.form}>
      <HomeAreaField
        hasHome={hasHome}
        label={homeLabel}
        error={errors.home}
        disabled={pending}
        onSelect={(place) => {
          setHomeLatitude(place.lat);
          setHomeLongitude(place.lng);
          setHomeLabel(place.secondary || place.primary);
          setErrors((current) => ({ ...current, home: undefined }));
        }}
        onRemove={() => {
          setHomeLatitude(null);
          setHomeLongitude(null);
          setHomeLabel('');
        }}
      />

      <TimeField
        label="When do you usually head home?"
        value={defaultReturnTime}
        disabled={pending}
        testIDPrefix="smartReturn.settings.time"
        onChange={setDefaultReturnTime}
      />

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Advanced settings"
        accessibilityState={{ expanded: advancedOpen }}
        hitSlop={HIT_SLOP}
        testID="smartReturn.settings.advanced"
        onPress={() => setAdvancedOpen((open) => !open)}
        style={styles.advancedToggle}
      >
        <Ionicons
          name={advancedOpen ? 'chevron-up' : 'chevron-down'}
          size={16}
          color={theme.colors.textMuted}
        />
        <AppText variant="label" tone="muted">
          Advanced
        </AppText>
      </Pressable>

      {advancedOpen ? (
        <View style={styles.group}>
          <AppText variant="caption" tone="muted">
            How early should we check? (minutes)
          </AppText>
          <TextInput
            testID="smartReturn.settings.leadMinutes"
            accessibilityLabel="How early should we check, in minutes"
            value={leadMinutes}
            editable={!pending}
            onChangeText={setLeadMinutes}
            keyboardType="number-pad"
            inputMode="numeric"
            placeholderTextColor={theme.colors.textMuted}
            style={[
              styles.input,
              {
                minHeight: MIN_TOUCH_TARGET,
                color: theme.colors.text,
                backgroundColor: theme.colors.surface,
                borderColor: errors.leadMinutes ? theme.colors.danger : theme.colors.border,
                borderRadius: theme.radius.md,
                paddingHorizontal: theme.spacing.md,
              },
            ]}
          />
          {errors.leadMinutes ? (
            <AppText variant="caption" tone="danger" accessibilityRole="alert">
              {errors.leadMinutes}
            </AppText>
          ) : null}
        </View>
      ) : null}

      <View style={styles.actions}>
        <Button label={submitLabel} testID="smartReturn.settings.save" onPress={submit} loading={pending} />
        {allowTurnOff ? (
          <Button
            label="Turn off Smart Return"
            testID="smartReturn.settings.turnOff"
            variant="ghost"
            onPress={turnOff}
            disabled={pending}
          />
        ) : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  form: { gap: 16 },
  group: { gap: 6 },
  input: { borderWidth: 1, fontSize: 15 },
  advancedToggle: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    alignSelf: 'flex-start',
    minHeight: 32,
  },
  actions: { gap: 8 },
});
