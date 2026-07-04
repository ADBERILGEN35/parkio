import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import type { SmartReturnSettings } from '@parkio/types';
import { AppText, Button, Card, Screen, Skeleton, StateView } from '@/components/ui';
import { HIT_SLOP, useTheme } from '@/theme';
import { SettingsForm } from '../components/SettingsForm';
import { TodayCard } from '../components/TodayCard';
import { useSmartReturnQuery } from '../hooks/useSmartReturnSettings';

/** Benefits surfaced on the empty state so opting in reads as a feature, not a form. */
const BENEFITS = [
  { icon: 'time-outline', text: 'One automatic parking check before you head home.' },
  { icon: 'notifications-outline', text: 'A heads-up only when a spot opens near your home area.' },
  { icon: 'shield-checkmark-outline', text: 'Private by design — no live location tracking.' },
] as const;

/**
 * Native Smart Return screen — the mobile counterpart of the web profile's
 * SmartReturnCard. Progressive flow: not configured → benefits + enable form;
 * configured → Today card first, settings collapsed underneath.
 */
export function SmartReturnScreen() {
  const query = useSmartReturnQuery();

  if (query.isPending) {
    return (
      <Screen contentStyle={styles.content} testID="smartReturn.loading">
        <IntroCopy />
        <Card style={styles.skeletonCard}>
          <View style={styles.skeletonHeader}>
            <Skeleton width="30%" height={18} />
            <Skeleton width={64} height={22} />
          </View>
          <Skeleton width="70%" height={13} />
          <Skeleton height={44} radius={999} />
          <Skeleton height={44} radius={999} />
        </Card>
      </Screen>
    );
  }

  if (query.isError) {
    return (
      <Screen scroll={false}>
        <StateView
          icon="cloud-offline-outline"
          title="Couldn’t load your Smart Return settings"
          description="Check your connection and try again."
          actionLabel={query.isFetching ? 'Retrying…' : 'Try again'}
          onAction={() => void query.refetch()}
        />
      </Screen>
    );
  }

  const settings = query.data;
  const configured = settings.enabled && settings.homeLatitude !== null && settings.homeLongitude !== null;

  return (
    <Screen contentStyle={styles.content}>
      <IntroCopy />
      {configured ? (
        <>
          <TodayCard settings={settings} />
          <SettingsSection settings={settings} />
        </>
      ) : (
        <SetupView settings={settings} />
      )}
    </Screen>
  );
}

function IntroCopy() {
  return (
    <AppText variant="body" tone="muted">
      One parking check near your saved home area, right before you head back.
    </AppText>
  );
}

/* -------------------------------------------------------------------------- */
/* Empty / setup state                                                        */
/* -------------------------------------------------------------------------- */

function SetupView({ settings }: { settings: SmartReturnSettings }) {
  const theme = useTheme();
  const [setupOpen, setSetupOpen] = useState(false);

  return (
    <View style={styles.stack}>
      <Card style={styles.setupCard}>
        <View style={[styles.heroDisc, { backgroundColor: theme.colors.primarySoft, borderRadius: theme.radius.full }]}>
          <Ionicons name="home" size={34} color={theme.colors.primary} />
        </View>
        <AppText variant="subtitle" style={styles.centered}>
          Never circle the block again
        </AppText>
        <AppText variant="body" tone="muted" style={styles.centered}>
          Smart Return checks for parking near home so a spot is waiting when you get back.
        </AppText>

        <View style={styles.benefits}>
          {BENEFITS.map((benefit) => (
            <View key={benefit.text} style={styles.benefitRow}>
              <Ionicons name={benefit.icon} size={18} color={theme.colors.primary} style={styles.benefitIcon} />
              <AppText variant="body" tone="muted" style={styles.benefitText}>
                {benefit.text}
              </AppText>
            </View>
          ))}
        </View>

        {!setupOpen ? (
          <Button
            label="Enable Smart Return"
            testID="smartReturn.setup.enable"
            leading={<Ionicons name="add" size={18} color={theme.colors.onPrimary} />}
            onPress={() => setSetupOpen(true)}
          />
        ) : null}
      </Card>

      {setupOpen ? (
        <Card>
          <SettingsForm settings={settings} submitLabel="Turn on Smart Return" />
        </Card>
      ) : null}

      <PrivacyNote />
    </View>
  );
}

function PrivacyNote() {
  const theme = useTheme();
  return (
    <Card elevated={false} style={styles.privacyCard}>
      <View style={styles.privacyTitleRow}>
        <Ionicons name="shield-checkmark" size={16} color={theme.colors.primary} />
        <AppText variant="label">Private by design</AppText>
      </View>
      <AppText variant="body" tone="muted">
        Your saved home area is stored only after you opt in. It powers Smart Return checks, is never shown to
        other people, never goes into analytics, and your live location is never tracked.
      </AppText>
    </Card>
  );
}

/* -------------------------------------------------------------------------- */
/* Settings — secondary, collapsed by default                                 */
/* -------------------------------------------------------------------------- */

function SettingsSection({ settings }: { settings: SmartReturnSettings }) {
  const theme = useTheme();
  const [open, setOpen] = useState(false);

  return (
    <Card padded={false} elevated={false}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Smart Return settings"
        accessibilityState={{ expanded: open }}
        hitSlop={HIT_SLOP}
        testID="smartReturn.settings.toggle"
        onPress={() => setOpen((value) => !value)}
        style={styles.settingsHeader}
      >
        <View style={styles.settingsTitleRow}>
          <Ionicons name="options-outline" size={18} color={theme.colors.primary} />
          <AppText variant="label">Smart Return settings</AppText>
        </View>
        <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={20} color={theme.colors.textMuted} />
      </Pressable>
      {open ? (
        <View style={[styles.settingsBody, { borderTopColor: theme.colors.border }]}>
          <SettingsForm settings={settings} submitLabel="Save changes" allowTurnOff />
        </View>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  stack: { gap: 16 },
  skeletonCard: { gap: 12 },
  skeletonHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  setupCard: { alignItems: 'stretch', gap: 12 },
  heroDisc: { width: 64, height: 64, alignItems: 'center', justifyContent: 'center', alignSelf: 'center' },
  centered: { textAlign: 'center' },
  benefits: { gap: 8, marginVertical: 4 },
  benefitRow: { flexDirection: 'row', gap: 8 },
  benefitIcon: { marginTop: 2 },
  benefitText: { flex: 1 },
  privacyCard: { gap: 6 },
  privacyTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  settingsHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
    minHeight: 48,
  },
  settingsTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  settingsBody: { borderTopWidth: 1, padding: 16 },
});
