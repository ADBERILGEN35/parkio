import { forwardRef, useState } from 'react';
import {
  StyleSheet,
  TextInput,
  View,
  type StyleProp,
  type TextInputProps,
  type ViewStyle,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from './AppText';
import { fonts, radius } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';
import { useT } from '@/i18n/LocaleProvider';

export interface TextFieldProps extends Omit<TextInputProps, 'style'> {
  label?: string;
  helper?: string;
  error?: string | null;
  /** Mono trace id shown under an error (support handle). */
  traceId?: string | null;
  /** Renders the show/hide eye and secures the entry. */
  password?: boolean;
  leadingIcon?: keyof typeof MaterialCommunityIcons.glyphMap;
  containerStyle?: StyleProp<ViewStyle>;
}

/**
 * Text input per the pen kit: 8px radius, outline-variant stroke,
 * focus = 2px primary ring, error state with message + optional trace id.
 */
export const TextField = forwardRef<TextInput, TextFieldProps>(function TextField(
  { label, helper, error, traceId, password, leadingIcon, containerStyle, onFocus, onBlur, ...rest },
  ref,
) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const [focused, setFocused] = useState(false);
  const [hidden, setHidden] = useState(Boolean(password));

  const borderColor = error ? colors.error : focused ? colors.primary : colors.outlineVariant;

  return (
    <View style={[styles.container, containerStyle]}>
      {label ? (
        <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.label}>
          {label}
        </AppText>
      ) : null}
      <View
        style={[
          styles.field,
          {
            borderColor,
            borderWidth: focused || error ? 2 : 1,
            backgroundColor: colors.surface,
            borderRadius: radius.input,
            // Keep inner height stable as the border grows on focus.
            paddingHorizontal: focused || error ? 13 : 14,
          },
        ]}
      >
        {leadingIcon && (
          <MaterialCommunityIcons name={leadingIcon} size={18} color={colors.onSurfaceVariant} />
        )}
        <TextInput
          ref={ref}
          {...rest}
          secureTextEntry={hidden}
          onFocus={(event) => {
            setFocused(true);
            onFocus?.(event);
          }}
          onBlur={(event) => {
            setFocused(false);
            onBlur?.(event);
          }}
          placeholderTextColor={colors.outline}
          style={[
            styles.input,
            {
              color: colors.onSurface,
              fontFamily: fonts.regular,
            },
          ]}
          accessibilityLabel={label ?? rest.placeholder}
        />
        {password && (
          <MaterialCommunityIcons
            name={hidden ? 'eye-outline' : 'eye-off-outline'}
            size={20}
            color={colors.onSurfaceVariant}
            onPress={() => setHidden((value) => !value)}
            accessibilityRole="button"
            accessibilityLabel={hidden ? t('auth.passwordShow') : t('auth.passwordHide')}
            suppressHighlighting
          />
        )}
      </View>
      {error ? (
        <View style={styles.helperRow}>
          <AppText variant="bodySm" color={colors.error}>
            {error}
          </AppText>
          {traceId ? (
            <AppText
              variant="labelSm"
              color={colors.outline}
              style={{ fontFamily: 'Courier' }}
              numberOfLines={1}
            >
              {t('common.error.trace', { id: traceId })}
            </AppText>
          ) : null}
        </View>
      ) : helper ? (
        <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.helperRow}>
          {helper}
        </AppText>
      ) : null}
    </View>
  );
});

const styles = StyleSheet.create({
  container: { gap: 6 },
  label: {},
  field: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    minHeight: 50,
  },
  input: {
    flex: 1,
    fontSize: 16,
    paddingVertical: 12,
  },
  helperRow: { gap: 2 },
});
