import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import type { SmartReturnSettings, SmartReturnTodayStatus } from '@parkio/types';
import { AppText, Badge, Button, Card, type BadgeTone } from '@/components/ui';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme';
import { useSmartReturnMutations } from '../hooks/useSmartReturnSettings';
import { checkTimeFromIso, checkTimeFromValue, formatClock, initialReturnTime, todayAt } from '../lib/time';
import { TimeField } from './TimeField';

type TodayMode = 'idle' | 'pickTime';

/**
 * Web `TodayCard`, translated: status badge + the progressive daily flow —
 * "Are you driving today?" → return-time picker → active confirmation with
 * edit/cancel. All four states write the server's response straight back into
 * the shared settings cache via the mutations hook.
 */
export function TodayCard({ settings }: { settings: SmartReturnSettings }) {
  const toast = useToast();
  const { leftByCar, notByCar, cancelToday } = useSmartReturnMutations();
  const [mode, setMode] = useState<TodayMode>('idle');

  const active =
    settings.todayStatus === 'LEFT_BY_CAR' || settings.todayStatus === 'RETURN_CHECK_IN_PROGRESS';
  const busy = leftByCar.isPending || notByCar.isPending || cancelToday.isPending;

  const saveReturnTime = (returnTime: string) => {
    const expectedReturnAt = todayAt(returnTime);
    if (!expectedReturnAt || expectedReturnAt.getTime() <= Date.now()) {
      toast.showError('Pick a return time later today.');
      return;
    }
    leftByCar.mutate(
      { expectedReturnAt: expectedReturnAt.toISOString() },
      {
        onSuccess: () => {
          setMode('idle');
          toast.showSuccess("Today's Smart Return is set.");
        },
        onError: () => toast.showError('We could not save your plan. Please try again.'),
      },
    );
  };

  const answerNotByCar = () =>
    notByCar.mutate(undefined, {
      onSuccess: () => {
        setMode('idle');
        toast.showSuccess('No Smart Return today.');
      },
      onError: () => toast.showError('We could not update today’s plan. Please try again.'),
    });

  const cancel = () =>
    cancelToday.mutate(undefined, {
      onSuccess: () => {
        setMode('idle');
        toast.showSuccess('Today’s reminder cancelled.');
      },
      onError: () => toast.showError('We could not cancel today’s reminder. Please try again.'),
    });

  return (
    <Card style={styles.card}>
      <View style={styles.header}>
        <AppText variant="subtitle">Today</AppText>
        <TodayStatusBadge status={settings.todayStatus} />
      </View>

      {mode === 'pickTime' ? (
        <TimePicker
          settings={settings}
          pending={leftByCar.isPending}
          onSave={saveReturnTime}
          onCancel={active || settings.todayStatus === 'NOT_BY_CAR' ? () => setMode('idle') : undefined}
        />
      ) : active ? (
        <ActivePlan settings={settings} busy={busy} onEdit={() => setMode('pickTime')} onCancel={cancel} />
      ) : settings.todayStatus === 'NOT_BY_CAR' ? (
        <NotDrivingState busy={busy} onChangedMind={() => setMode('pickTime')} />
      ) : (
        <DrivingPrompt
          cancelled={settings.todayStatus === 'CANCELLED'}
          busy={busy}
          onYes={() => setMode('pickTime')}
          onNo={answerNotByCar}
        />
      )}
    </Card>
  );
}

function DrivingPrompt({
  cancelled,
  busy,
  onYes,
  onNo,
}: {
  cancelled: boolean;
  busy: boolean;
  onYes: () => void;
  onNo: () => void;
}) {
  const theme = useTheme();
  return (
    <View style={styles.section}>
      {cancelled ? (
        <AppText variant="caption" tone="muted">
          Today’s reminder was cancelled. Driving again?
        </AppText>
      ) : null}
      <AppText variant="body">Are you driving today?</AppText>
      <Button
        label="Yes, driving"
        testID="smartReturn.today.yes"
        leading={<Ionicons name="car" size={18} color={theme.colors.onPrimary} />}
        disabled={busy}
        onPress={onYes}
      />
      <Button label="Not by car" testID="smartReturn.today.no" variant="secondary" disabled={busy} onPress={onNo} />
    </View>
  );
}

function TimePicker({
  settings,
  pending,
  onSave,
  onCancel,
}: {
  settings: SmartReturnSettings;
  pending: boolean;
  onSave: (returnTime: string) => void;
  onCancel?: () => void;
}) {
  const theme = useTheme();
  const [returnTime, setReturnTime] = useState(() => initialReturnTime(settings));
  const previewCheck = checkTimeFromValue(returnTime, settings.reminderLeadMinutes);

  return (
    <View style={styles.section}>
      <TimeField
        label="Expected return time"
        value={returnTime}
        disabled={pending}
        testIDPrefix="smartReturn.today.time"
        onChange={setReturnTime}
      />
      {previewCheck ? (
        <View style={styles.previewRow}>
          <Ionicons name="time-outline" size={15} color={theme.colors.primary} />
          <AppText variant="caption" tone="muted">
            We’ll check around {previewCheck}.
          </AppText>
        </View>
      ) : null}
      <Button
        label={pending ? 'Saving…' : "Save today's plan"}
        testID="smartReturn.today.save"
        loading={pending}
        onPress={() => onSave(returnTime)}
      />
      {onCancel ? (
        <Button
          label="Cancel"
          testID="smartReturn.today.cancelPick"
          variant="ghost"
          disabled={pending}
          onPress={onCancel}
        />
      ) : null}
    </View>
  );
}

function ActivePlan({
  settings,
  busy,
  onEdit,
  onCancel,
}: {
  settings: SmartReturnSettings;
  busy: boolean;
  onEdit: () => void;
  onCancel: () => void;
}) {
  const theme = useTheme();
  const returnAt = settings.todayExpectedReturnAt ? formatClock(settings.todayExpectedReturnAt) : null;
  const checkAt = settings.todayExpectedReturnAt
    ? checkTimeFromIso(settings.todayExpectedReturnAt, settings.reminderLeadMinutes)
    : null;

  return (
    <View style={styles.section}>
      <View style={[styles.activePanel, { backgroundColor: theme.colors.primarySoft, borderRadius: theme.radius.lg }]}>
        <View style={styles.activeTitleRow}>
          <Ionicons name="checkmark-circle" size={18} color={theme.colors.primary} />
          <AppText variant="label" tone="primary">
            Today’s Smart Return is active
          </AppText>
        </View>
        <View style={styles.activeFacts}>
          <View style={styles.fact}>
            <AppText variant="caption" tone="muted">
              Returning around
            </AppText>
            <AppText variant="subtitle">{returnAt ?? '—'}</AppText>
          </View>
          <View style={styles.fact}>
            <AppText variant="caption" tone="muted">
              We’ll check around
            </AppText>
            <AppText variant="subtitle">{checkAt ?? '—'}</AppText>
          </View>
        </View>
      </View>
      <Button label="Edit" testID="smartReturn.today.edit" variant="secondary" disabled={busy} onPress={onEdit} />
      <Button label="Cancel" testID="smartReturn.today.cancel" variant="ghost" disabled={busy} onPress={onCancel} />
    </View>
  );
}

function NotDrivingState({ busy, onChangedMind }: { busy: boolean; onChangedMind: () => void }) {
  const theme = useTheme();
  return (
    <View style={styles.section}>
      <View style={styles.previewRow}>
        <Ionicons name="remove-circle-outline" size={17} color={theme.colors.textMuted} />
        <AppText variant="body" tone="muted">
          No Smart Return scheduled today.
        </AppText>
      </View>
      <Button
        label="I’m driving after all"
        testID="smartReturn.today.changedMind"
        variant="ghost"
        disabled={busy}
        onPress={onChangedMind}
      />
    </View>
  );
}

function TodayStatusBadge({ status }: { status: SmartReturnTodayStatus }) {
  const badge: Record<SmartReturnTodayStatus, { label: string; tone: BadgeTone }> = {
    LEFT_BY_CAR: { label: 'Active', tone: 'primary' },
    RETURN_CHECK_IN_PROGRESS: { label: 'Checking', tone: 'primary' },
    NOT_BY_CAR: { label: 'Not today', tone: 'neutral' },
    CANCELLED: { label: 'Cancelled', tone: 'neutral' },
    UNKNOWN: { label: 'Not set', tone: 'neutral' },
  };
  const { label, tone } = badge[status] ?? badge.UNKNOWN;
  return <Badge label={label} tone={tone} />;
}

const styles = StyleSheet.create({
  card: { gap: 12 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  section: { gap: 10 },
  previewRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  activePanel: { padding: 14, gap: 10 },
  activeTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  activeFacts: { flexDirection: 'row', gap: 16 },
  fact: { flex: 1, gap: 2 },
});
