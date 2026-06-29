import type { ReactNode } from 'react';
import { View, type ViewStyle } from 'react-native';
import { useTheme } from '@/theme';

export interface CardProps {
  children: ReactNode;
  style?: ViewStyle;
  /** Add a soft drop shadow for raised cards. */
  elevated?: boolean;
  padded?: boolean;
}

/** Rounded surface container — the building block for most screen content. */
export function Card({ children, style, elevated = true, padded = true }: CardProps) {
  const theme = useTheme();
  return (
    <View
      style={[
        {
          backgroundColor: theme.colors.surface,
          borderRadius: theme.radius.xl,
          borderWidth: 1,
          borderColor: theme.colors.border,
          padding: padded ? theme.spacing.lg : 0,
        },
        elevated ? theme.elevation.card : null,
        style,
      ]}
    >
      {children}
    </View>
  );
}
