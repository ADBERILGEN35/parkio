import { Pressable, StyleSheet } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Glass } from '@/components/ui/Glass';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

export type AssistantEntryControlProps = {
  onPress: () => void;
};

/** Compact “Nereye gidiyorsun?” entry — distinct from area discovery search. */
export function AssistantEntryControl({ onPress }: AssistantEntryControlProps) {
  const t = useT();
  const { colors } = useTheme();
  const label = t('assistant.entryPrompt');

  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={label}
      testID="assistant-entry"
      style={({ pressed }) => [styles.pressable, pressed && styles.pressed]}
    >
      <Glass radius={999} contentStyle={styles.content}>
        <MaterialCommunityIcons name="map-marker-path" size={18} color={colors.primary} />
        <AppText variant="labelMd" color={colors.onSurface} numberOfLines={1} style={styles.label}>
          {label}
        </AppText>
        <MaterialCommunityIcons name="chevron-right" size={18} color={colors.onSurfaceVariant} />
      </Glass>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  pressable: { alignSelf: 'stretch' },
  pressed: { opacity: 0.88 },
  content: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 14,
    paddingVertical: 10,
    minHeight: 44,
  },
  label: { flex: 1 },
});
