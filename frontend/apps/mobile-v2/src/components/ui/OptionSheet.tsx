import { ScrollView, StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from './AppText';
import { PressableScale } from './PressableScale';
import { Sheet } from './Sheet';
import { radius } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface SheetOption<T extends string> {
  value: T;
  label: string;
  description?: string;
  icon?: keyof typeof MaterialCommunityIcons.glyphMap;
  tone?: 'default' | 'danger';
}

export interface OptionSheetProps<T extends string> {
  visible: boolean;
  onClose: () => void;
  title: string;
  hint?: string;
  options: SheetOption<T>[];
  selected?: T | null;
  onSelect: (value: T) => void;
}

/** Bottom-sheet option list — the select control + verify/report choosers. */
export function OptionSheet<T extends string>({
  visible,
  onClose,
  title,
  hint,
  options,
  selected,
  onSelect,
}: OptionSheetProps<T>) {
  const theme = useTheme();
  const { colors } = theme;

  return (
    <Sheet visible={visible} onClose={onClose} title={title}>
      {hint ? (
        <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.hint}>
          {hint}
        </AppText>
      ) : null}
      <ScrollView bounces={false} style={styles.list}>
        <View style={styles.listInner}>
          {options.map((option) => {
            const isSelected = option.value === selected;
            const danger = option.tone === 'danger';
            const fg = danger ? colors.error : colors.onSurface;
            return (
              <PressableScale
                key={option.value}
                scaleTo={0.98}
                onPress={() => onSelect(option.value)}
                accessibilityRole="button"
                accessibilityLabel={option.label}
                accessibilityState={{ selected: isSelected }}
                style={[
                  styles.option,
                  {
                    backgroundColor: isSelected ? colors.primaryFixed : colors.surfaceContainer1,
                    borderRadius: radius.input + 4,
                  },
                ]}
              >
                {option.icon && (
                  <MaterialCommunityIcons
                    name={option.icon}
                    size={20}
                    color={danger ? colors.error : isSelected ? colors.primary : colors.onSurfaceVariant}
                  />
                )}
                <View style={styles.optionLabels}>
                  <AppText
                    variant="bodyLg"
                    color={isSelected && !danger ? colors.primary : fg}
                    numberOfLines={2}
                  >
                    {option.label}
                  </AppText>
                  {option.description ? (
                    <AppText variant="bodySm" color={colors.onSurfaceVariant} numberOfLines={2}>
                      {option.description}
                    </AppText>
                  ) : null}
                </View>
                {isSelected && (
                  <MaterialCommunityIcons name="check-circle" size={20} color={colors.primary} />
                )}
              </PressableScale>
            );
          })}
        </View>
      </ScrollView>
    </Sheet>
  );
}

const styles = StyleSheet.create({
  hint: { marginTop: -6 },
  list: { maxHeight: 440 },
  listInner: { gap: 8, paddingBottom: 4 },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 14,
    paddingVertical: 13,
  },
  optionLabels: { flex: 1, gap: 2 },
});
