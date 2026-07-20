import { StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { passwordRequirementState } from '@parkio/validation';
import { AppText } from '@/components/ui/AppText';
import { useT } from '@/i18n/LocaleProvider';
import type { TranslationKey } from '@/i18n/translations';
import { useTheme } from '@/theme/ThemeProvider';

const RULES: { id: keyof ReturnType<typeof passwordRequirementState>; key: TranslationKey }[] = [
  { id: 'length', key: 'auth.passwordRule.length' },
  { id: 'uppercase', key: 'auth.passwordRule.upper' },
  { id: 'lowercase', key: 'auth.passwordRule.lower' },
  { id: 'digit', key: 'auth.passwordRule.digit' },
  { id: 'notCommon', key: 'auth.passwordRule.common' },
];

/** Live checklist mirroring the backend's password policy (shared zod rules). */
export function PasswordChecklist({ password }: { password: string }) {
  const theme = useTheme();
  const t = useT();
  const state = passwordRequirementState(password);
  return (
    <View style={styles.grid}>
      {RULES.map((rule) => {
        const ok = state[rule.id];
        return (
          <View key={rule.id} style={styles.item}>
            <MaterialCommunityIcons
              name={ok ? 'check-circle' : 'circle-outline'}
              size={14}
              color={ok ? theme.colors.secondary : theme.colors.outline}
            />
            <AppText
              variant="bodySm"
              color={ok ? theme.colors.onSurface : theme.colors.onSurfaceVariant}
            >
              {t(rule.key)}
            </AppText>
          </View>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, rowGap: 5 },
  item: { flexDirection: 'row', alignItems: 'center', gap: 5, minWidth: '45%' },
});
