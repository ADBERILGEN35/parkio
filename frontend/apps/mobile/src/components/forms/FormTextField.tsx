import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { useController, type Control, type FieldValues, type Path } from 'react-hook-form';
import { Pressable, StyleSheet, TextInput, View, type TextInputProps } from 'react-native';
import { HIT_SLOP, MIN_TOUCH_TARGET, useTheme } from '@/theme';
import { AppText } from '@/components/ui/AppText';
import { useLocale } from '@/i18n/LocaleProvider';

export interface FormTextFieldProps<T extends FieldValues> extends Omit<TextInputProps, 'value' | 'onChangeText'> {
  control: Control<T>;
  name: Path<T>;
  label: string;
  /** Visually-hidden helper read by screen readers when no error is present. */
  hint?: string;
}

/**
 * React Hook Form-bound text input with a label, inline validation error, and
 * accessible wiring (label as accessibilityLabel, error announced via
 * accessibilityState/`alert`). Honors the 44pt min height.
 *
 * Visuals follow the web V2 input: white field on a hairline ring that swaps to
 * the primary color on focus (error keeps the error ring).
 */
export function FormTextField<T extends FieldValues>({
  control,
  name,
  label,
  hint,
  onFocus,
  secureTextEntry,
  placeholder,
  ...inputProps
}: FormTextFieldProps<T>) {
  const theme = useTheme();
  const { t } = useLocale();
  const { field, fieldState } = useController({ control, name });
  const error = fieldState.error?.message;
  const [focused, setFocused] = useState(false);
  // Secure fields get the standard visibility toggle (eye icon).
  const [revealed, setRevealed] = useState(false);
  const secure = Boolean(secureTextEntry);

  const borderColor = error
    ? theme.colors.danger
    : focused
      ? theme.colors.primary
      : theme.colors.border;

  return (
    <View style={styles.group}>
      <AppText variant="caption" tone="muted" style={styles.label}>
        {label}
      </AppText>
      <View>
        <TextInput
          accessibilityLabel={t(label)}
          accessibilityHint={error ? undefined : hint ? t(hint) : undefined}
          value={field.value ?? ''}
          onChangeText={field.onChange}
          onFocus={(event) => {
            setFocused(true);
            onFocus?.(event);
          }}
          onBlur={() => {
            setFocused(false);
            field.onBlur();
          }}
          secureTextEntry={secure && !revealed}
          placeholderTextColor={theme.colors.textMuted}
          placeholder={placeholder ? t(placeholder) : undefined}
          style={[
            styles.input,
            {
              minHeight: MIN_TOUCH_TARGET,
              color: theme.colors.text,
              backgroundColor: theme.colors.surface,
              borderColor,
              borderRadius: theme.radius.md,
              paddingHorizontal: theme.spacing.md,
              paddingRight: secure ? 44 : theme.spacing.md,
            },
            theme.elevation.card,
          ]}
          {...inputProps}
        />
        {secure ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t(revealed ? 'Hide password' : 'Show password')}
            hitSlop={HIT_SLOP}
            onPress={() => setRevealed((v) => !v)}
            style={styles.reveal}
          >
            <Ionicons
              name={revealed ? 'eye-off-outline' : 'eye-outline'}
              size={20}
              color={theme.colors.textMuted}
            />
          </Pressable>
        ) : null}
      </View>
      {error ? (
        <AppText variant="caption" tone="danger" accessibilityRole="alert" style={styles.error}>
          {error}
        </AppText>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  group: { gap: 6 },
  label: {},
  input: { borderWidth: 1, fontSize: 15 },
  reveal: {
    position: 'absolute',
    right: 12,
    top: 0,
    bottom: 0,
    justifyContent: 'center',
  },
  error: {},
});
