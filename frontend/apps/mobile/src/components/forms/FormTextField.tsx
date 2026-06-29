import { useController, type Control, type FieldValues, type Path } from 'react-hook-form';
import { StyleSheet, TextInput, View, type TextInputProps } from 'react-native';
import { MIN_TOUCH_TARGET, useTheme } from '@/theme';
import { AppText } from '@/components/ui/AppText';

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
 */
export function FormTextField<T extends FieldValues>({
  control,
  name,
  label,
  hint,
  ...inputProps
}: FormTextFieldProps<T>) {
  const theme = useTheme();
  const { field, fieldState } = useController({ control, name });
  const error = fieldState.error?.message;

  return (
    <View style={styles.group}>
      <AppText variant="label" tone="muted" style={styles.label}>
        {label}
      </AppText>
      <TextInput
        accessibilityLabel={label}
        accessibilityHint={error ? undefined : hint}
        value={field.value ?? ''}
        onChangeText={field.onChange}
        onBlur={field.onBlur}
        placeholderTextColor={theme.colors.textMuted}
        style={[
          styles.input,
          {
            minHeight: MIN_TOUCH_TARGET,
            color: theme.colors.text,
            backgroundColor: theme.colors.surface,
            borderColor: error ? theme.colors.danger : theme.colors.border,
            borderRadius: theme.radius.md,
            paddingHorizontal: theme.spacing.lg,
          },
        ]}
        {...inputProps}
      />
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
  input: { borderWidth: 1, fontSize: 16 },
  error: {},
});
