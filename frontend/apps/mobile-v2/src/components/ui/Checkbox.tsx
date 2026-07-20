import { Pressable, StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from './AppText';
import { useTheme } from '@/theme/ThemeProvider';

export interface CheckboxProps {
  checked: boolean;
  onToggle: () => void;
  label?: string;
  /** Inline rich label (e.g. consent text with links). */
  children?: React.ReactNode;
  disabled?: boolean;
  error?: boolean;
}

export function Checkbox({ checked, onToggle, label, children, disabled, error }: CheckboxProps) {
  const theme = useTheme();
  const { colors } = theme;
  return (
    <Pressable
      onPress={onToggle}
      disabled={disabled}
      accessibilityRole="checkbox"
      accessibilityState={{ checked, disabled: Boolean(disabled) }}
      accessibilityLabel={label}
      style={[styles.row, { opacity: disabled ? 0.45 : 1 }]}
      hitSlop={6}
    >
      <View
        style={[
          styles.box,
          {
            backgroundColor: checked ? colors.primary : colors.surface,
            borderColor: checked ? colors.primary : error ? colors.error : colors.outlineVariant,
          },
        ]}
      >
        {checked && <MaterialCommunityIcons name="check" size={14} color={colors.onPrimary} />}
      </View>
      {children ?? (
        <AppText variant="bodyMd" style={styles.label}>
          {label}
        </AppText>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'flex-start', gap: 10 },
  box: {
    width: 20,
    height: 20,
    borderRadius: 5,
    borderWidth: 1.5,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 1,
  },
  label: { flex: 1 },
});
