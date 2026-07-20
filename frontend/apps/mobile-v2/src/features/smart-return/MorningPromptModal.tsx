import { useState } from 'react';
import { Modal, Pressable, StyleSheet, View } from 'react-native';
import Animated, { FadeIn, ZoomIn } from 'react-native-reanimated';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { IconButton } from '@/components/ui/IconButton';
import { useT } from '@/i18n/LocaleProvider';
import { parseTimeOfDay } from '@/lib/time';
import { radius, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface MorningPromptModalProps {
  visible: boolean;
  /** "HH:mm" default from settings (or 18:00). */
  defaultTime: string;
  submitting?: boolean;
  onYes: (hours: number, minutes: number) => void;
  onNo: () => void;
  onDismiss: () => void;
}

/**
 * Morning prompt (pen `zQkiP`): "Bugün arabayla mı çıktın?" with an editable
 * expected-return time (15-minute stepper — no native picker dependency).
 */
export function MorningPromptModal({
  visible,
  defaultTime,
  submitting,
  onYes,
  onNo,
  onDismiss,
}: MorningPromptModalProps) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const parsed = parseTimeOfDay(defaultTime) ?? { hours: 18, minutes: 0 };
  const [minutesTotal, setMinutesTotal] = useState(parsed.hours * 60 + parsed.minutes);
  const [editing, setEditing] = useState(false);

  const hh = Math.floor(minutesTotal / 60) % 24;
  const mm = minutesTotal % 60;
  const timeLabel = `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;

  const step = (delta: number) => {
    setMinutesTotal((value) => {
      const next = value + delta;
      return Math.min(23 * 60 + 45, Math.max(0, next));
    });
  };

  return (
    <Modal
      visible={visible}
      transparent
      animationType="none"
      statusBarTranslucent
      navigationBarTranslucent
      onRequestClose={onDismiss}
    >
      <View style={styles.host}>
        <Animated.View entering={FadeIn.duration(180)} style={StyleSheet.absoluteFill}>
          <Pressable
            style={[StyleSheet.absoluteFill, { backgroundColor: theme.colors.scrim }]}
            onPress={onDismiss}
          />
        </Animated.View>
        <Animated.View
          entering={ZoomIn.springify().damping(18).stiffness(220)}
          style={[styles.card, { backgroundColor: colors.surface, borderRadius: radius.modal }, shadows.ambientDeep]}
        >
          <View style={[styles.iconBubble, { backgroundColor: colors.primaryFixed }]}>
            <MaterialCommunityIcons name="car-outline" size={24} color={colors.primary} />
          </View>
          <AppText variant="titleLg" align="center">
            {t('smartReturn.morning.title')}
          </AppText>

          <Pressable
            onPress={() => setEditing((value) => !value)}
            accessibilityRole="button"
            accessibilityLabel={t('smartReturn.today.editTime')}
            style={[styles.timeChip, { backgroundColor: colors.surfaceContainer2 }]}
          >
            <MaterialCommunityIcons name="clock-outline" size={15} color={colors.onSurfaceVariant} />
            <AppText variant="bodySm" tabular>
              {t('smartReturn.morning.estimated', { time: timeLabel })}
            </AppText>
            <MaterialCommunityIcons name="pencil-outline" size={14} color={colors.primary} />
          </Pressable>

          {editing && (
            <View style={styles.stepper}>
              <IconButton
                icon="minus"
                size={38}
                variant="surface"
                elevated
                accessibilityLabel="-15"
                onPress={() => step(-15)}
              />
              <AppText variant="countdownLg" tabular>
                {timeLabel}
              </AppText>
              <IconButton
                icon="plus"
                size={38}
                variant="surface"
                elevated
                accessibilityLabel="+15"
                onPress={() => step(15)}
              />
            </View>
          )}

          <View style={styles.actions}>
            <Button
              label={t('smartReturn.morning.yes')}
              onPress={() => onYes(hh, mm)}
              loading={submitting}
            />
            <Button label={t('smartReturn.morning.no')} variant="ghost" onPress={onNo} disabled={submitting} />
          </View>
          <AppText variant="labelSm" align="center" color={colors.onSurfaceVariant}>
            {t('smartReturn.morning.privacy')}
          </AppText>
        </Animated.View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  host: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  card: { width: '100%', maxWidth: 400, padding: 24, gap: 12, alignItems: 'center' },
  iconBubble: {
    width: 52,
    height: 52,
    borderRadius: 26,
    alignItems: 'center',
    justifyContent: 'center',
  },
  timeChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 999,
  },
  stepper: { flexDirection: 'row', alignItems: 'center', gap: 18 },
  actions: { alignSelf: 'stretch', gap: 4, marginTop: 4 },
});
