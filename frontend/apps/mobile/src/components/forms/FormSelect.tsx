import { useController, type Control, type FieldValues, type Path } from 'react-hook-form';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui/AppText';
import { useTheme } from '@/theme';

export function FormSelect<T extends FieldValues>({
  control,
  name,
  label,
  options,
  placeholder = 'Select…',
}: {
  control: Control<T>;
  name: Path<T>;
  label: string;
  options: { value: string; label: string }[];
  placeholder?: string;
}) {
  const theme = useTheme();
  const { field, fieldState } = useController({ control, name });
  const error = fieldState.error?.message;
  const current = field.value == null ? '' : String(field.value);

  return (
    <View style={styles.group}>
      <AppText variant="caption" tone="muted">
        {label}
      </AppText>
      <View style={styles.options}>
        {options.map((opt) => {
          const selected = current === opt.value;
          return (
            <Pressable
              key={opt.value}
              accessibilityRole="button"
              accessibilityState={{ selected }}
              onPress={() => field.onChange(opt.value)}
              style={[
                styles.chip,
                {
                  borderColor: selected ? theme.colors.primary : theme.colors.border,
                  backgroundColor: selected ? theme.colors.primarySoft : theme.colors.surface,
                  borderRadius: theme.radius.full,
                },
              ]}
            >
              <AppText variant="callout" tone={selected ? 'primary' : 'default'}>
                {opt.label}
              </AppText>
            </Pressable>
          );
        })}
      </View>
      {!current ? (
        <AppText variant="caption" tone="muted">
          {placeholder}
        </AppText>
      ) : null}
      {error ? (
        <AppText variant="caption" tone="danger">
          {error}
        </AppText>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  group: { gap: 8 },
  options: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: { borderWidth: 1, paddingHorizontal: 12, paddingVertical: 8, minHeight: 44, justifyContent: 'center' },
});