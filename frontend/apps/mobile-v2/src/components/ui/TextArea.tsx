import { useState } from 'react';
import { StyleSheet, TextInput, View, type TextInputProps } from 'react-native';
import { AppText } from './AppText';
import { fonts, radius } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface TextAreaProps extends Omit<TextInputProps, 'style'> {
  label?: string;
  maxLength?: number;
  error?: string | null;
  minHeight?: number;
}

/** Multiline input with the live "n / max" counter from the pen kit. */
export function TextArea({
  label,
  maxLength = 1000,
  error,
  minHeight = 108,
  value,
  onFocus,
  onBlur,
  ...rest
}: TextAreaProps) {
  const theme = useTheme();
  const { colors } = theme;
  const [focused, setFocused] = useState(false);
  const length = value?.length ?? 0;
  const borderColor = error ? colors.error : focused ? colors.primary : colors.outlineVariant;

  return (
    <View style={styles.container}>
      {label ? (
        <AppText variant="bodySm" color={colors.onSurfaceVariant}>
          {label}
        </AppText>
      ) : null}
      <View
        style={{
          borderColor,
          borderWidth: focused || error ? 2 : 1,
          borderRadius: radius.input,
          backgroundColor: colors.surface,
          padding: focused || error ? 11 : 12,
        }}
      >
        <TextInput
          {...rest}
          value={value}
          multiline
          maxLength={maxLength}
          textAlignVertical="top"
          onFocus={(event) => {
            setFocused(true);
            onFocus?.(event);
          }}
          onBlur={(event) => {
            setFocused(false);
            onBlur?.(event);
          }}
          placeholderTextColor={colors.outline}
          style={{
            minHeight,
            fontSize: 15,
            lineHeight: 21,
            color: colors.onSurface,
            fontFamily: fonts.regular,
          }}
          accessibilityLabel={label ?? rest.placeholder}
        />
        <AppText variant="labelSm" tabular color={colors.outline} align="right">
          {length} / {maxLength}
        </AppText>
      </View>
      {error ? (
        <AppText variant="bodySm" color={colors.error}>
          {error}
        </AppText>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: 6 },
});
