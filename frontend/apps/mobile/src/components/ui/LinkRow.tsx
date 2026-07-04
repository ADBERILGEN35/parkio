import { Ionicons } from '@expo/vector-icons';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui/AppText';
import { Card } from '@/components/ui/Card';
import { useTheme } from '@/theme';

export function LinkRow({
  icon,
  title,
  description,
  onPress,
  testID,
}: {
  icon: keyof typeof Ionicons.glyphMap;
  title: string;
  description?: string;
  onPress: () => void;
  testID?: string;
}) {
  const theme = useTheme();
  return (
    <Card padded={false}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={title}
        testID={testID}
        onPress={onPress}
        style={({ pressed }) => [
          styles.row,
          { backgroundColor: pressed ? theme.colors.surfaceMuted : 'transparent', borderRadius: theme.radius.xl },
        ]}
      >
        <View style={[styles.disc, { backgroundColor: theme.colors.primarySoft, borderRadius: theme.radius.full }]}>
          <Ionicons name={icon} size={18} color={theme.colors.primary} />
        </View>
        <View style={styles.text}>
          <AppText variant="subtitle">{title}</AppText>
          {description ? (
            <AppText variant="caption" tone="muted">
              {description}
            </AppText>
          ) : null}
        </View>
        <Ionicons name="chevron-forward" size={18} color={theme.colors.textMuted} />
      </Pressable>
    </Card>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 14, minHeight: 44 },
  disc: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
  text: { flex: 1, gap: 2 },
});