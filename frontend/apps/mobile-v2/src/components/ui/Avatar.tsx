import { View } from 'react-native';
import { AppText } from './AppText';
import { useTheme } from '@/theme/ThemeProvider';

export interface AvatarProps {
  /** Display name or fallback id; initials are derived. */
  name: string;
  size?: number;
}

function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return '·';
  }
  const first = [...parts[0]][0] ?? '';
  const second = parts.length > 1 ? ([...parts[parts.length - 1]][0] ?? '') : '';
  return (first + second).toLocaleUpperCase('tr-TR');
}

/** Initials avatar on the primary-fixed tint. */
export function Avatar({ name, size = 36 }: AvatarProps) {
  const theme = useTheme();
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size / 2,
        backgroundColor: theme.colors.primaryFixed,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <AppText
        variant={size >= 48 ? 'titleMd' : 'labelSm'}
        color={theme.mode === 'dark' ? theme.colors.primaryFixedDim : theme.colors.primary}
      >
        {initialsOf(name)}
      </AppText>
    </View>
  );
}
