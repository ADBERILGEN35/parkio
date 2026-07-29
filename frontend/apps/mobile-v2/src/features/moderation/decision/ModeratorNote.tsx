import { StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Card } from '@/components/ui/Card';
import { useT } from '@/i18n/LocaleProvider';
import { radius } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

/** Dedicated moderator-authored note — never mixed into AI reason copy. */
export function ModeratorNote({ note }: { note: string | null | undefined }) {
  const t = useT();
  const theme = useTheme();
  const { colors } = theme;
  if (!note?.trim()) {
    return null;
  }

  return (
    <View testID="moderator-note" accessibilityLabel={t('decision.moderatorNote.aria')}>
      <Card
        tone={1}
        padding={12}
        shadow={false}
        style={[
          styles.wrap,
          {
            borderLeftWidth: 3,
            borderLeftColor: colors.tertiary,
          },
        ]}
      >
        <View style={styles.titleRow}>
          <MaterialCommunityIcons name="note-text-outline" size={16} color={colors.tertiary} />
          <AppText variant="labelSm" color={colors.tertiary}>
            {t('decision.moderatorNote.title')}
          </AppText>
        </View>
        <AppText variant="bodyMd" color={colors.onSurface}>
          {note.trim()}
        </AppText>
      </Card>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    gap: 8,
    borderRadius: radius.input,
  },
  titleRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
});
