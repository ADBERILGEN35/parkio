import { useRef, useState } from 'react';
import { Modal, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { DEFAULT_MAP_CENTER, DEFAULT_PICKER_ZOOM, LOCATED_ZOOM, type LatLng } from '@parkio/geo';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Chip } from '@/components/ui/Chip';
import { EmptyState } from '@/components/ui/EmptyState';
import { IconButton } from '@/components/ui/IconButton';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { TextField } from '@/components/ui/TextField';
import { Toggle } from '@/components/ui/Toggle';
import { MapSurface, type MapSurfaceHandle } from '@/features/map/MapSurface';
import { useLocation } from '@/features/map/hooks';
import { useSmartReturn, useSmartReturnMutations } from '@/features/smart-return/useSmartReturn';
import { appConfig } from '@/config/env';
import { useT } from '@/i18n/LocaleProvider';
import { formatClock, parseTimeOfDay } from '@/lib/time';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme/ThemeProvider';

const LEAD_OPTIONS = [5, 10, 15, 30, 45, 60, 90, 120];

/** Smart Return settings + today card (brief §12.8) — privacy-first, opt-in. */
export default function SmartReturnScreen() {
  const theme = useTheme();
  const t = useT();
  const toast = useToast();
  const insets = useSafeAreaInsets();
  const { colors } = theme;

  const settings = useSmartReturn();
  const mutations = useSmartReturnMutations();
  // Uncommitted-edit pattern: null = "not touched", display falls back to the
  // server value — no hydration effect needed.
  const [homeLabelEdit, setHomeLabelEdit] = useState<string | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [returnMinutesEdit, setReturnMinutesEdit] = useState<number | null>(null);

  const serverReturnMinutes = (() => {
    const parsed = settings.data?.defaultReturnTime
      ? parseTimeOfDay(settings.data.defaultReturnTime)
      : null;
    return parsed ? parsed.hours * 60 + parsed.minutes : 18 * 60;
  })();
  const homeLabel = homeLabelEdit ?? settings.data?.homeLabel ?? '';
  const returnMinutes = returnMinutesEdit ?? serverReturnMinutes;

  if (!appConfig.features.smartReturn) {
    return (
      <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
        <ScreenHeader title={t('smartReturn.title')} />
        <EmptyState title={t('smartReturn.beta')} body={t('smartReturn.masterHint')} />
      </SafeAreaView>
    );
  }

  const data = settings.data;
  const hh = Math.floor(returnMinutes / 60) % 24;
  const mm = returnMinutes % 60;
  const timeLabel = `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;

  const save = (
    body: Parameters<typeof mutations.updateSettings.mutate>[0],
    quiet = false,
  ) => {
    mutations.updateSettings.mutate(body, {
      onSuccess: () => {
        if (!quiet) {
          toast.show(t('smartReturn.saved'), 'success');
        }
      },
      onError: () => toast.show(t('common.error.generic'), 'error'),
    });
  };

  const toggleEnabled = (enabled: boolean) => {
    if (enabled && data?.homeLatitude == null) {
      toast.show(t('smartReturn.needsHome'));
      setPickerOpen(true);
      return;
    }
    save({ enabled }, true);
  };

  const stepTime = (delta: number) => {
    setReturnMinutesEdit(Math.min(23 * 60 + 45, Math.max(0, returnMinutes + delta)));
  };

  const saveTime = () => {
    save({ defaultReturnTime: `${timeLabel}:00` });
  };

  const todayActive =
    data?.todayStatus === 'LEFT_BY_CAR' || data?.todayStatus === 'RETURN_CHECK_IN_PROGRESS';

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader
        title={t('smartReturn.title')}
        trailing={<Chip label={t('smartReturn.beta')} size="sm" />}
      />
      {settings.isLoading || !data ? (
        <View style={styles.loading}>
          <Skeleton height={72} radius={16} />
          <Skeleton height={140} radius={16} />
        </View>
      ) : (
        <ScrollView
          contentContainerStyle={[styles.scroll, { paddingBottom: insets.bottom + 32 }]}
          showsVerticalScrollIndicator={false}
        >
          {/* Master toggle */}
          <Card style={styles.rowCard}>
            <View style={styles.rowBetween}>
              <View style={styles.rowLabels}>
                <AppText variant="titleMd">{t('smartReturn.master')}</AppText>
                <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                  {t('smartReturn.masterHint')}
                </AppText>
              </View>
              <Toggle
                value={data.enabled}
                onValueChange={toggleEnabled}
                accessibilityLabel={t('smartReturn.master')}
              />
            </View>
          </Card>

          {/* Today card */}
          {todayActive && data.todayExpectedReturnAt && (
            <Card tone={3} shadow={false} style={styles.rowCard}>
              <View style={styles.rowBetween}>
                <View style={styles.rowLabels}>
                  <AppText variant="titleMd" tabular>
                    {t('smartReturn.today.title', { time: formatClock(data.todayExpectedReturnAt) })}
                  </AppText>
                  <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                    {data.todayStatus === 'RETURN_CHECK_IN_PROGRESS'
                      ? t('smartReturn.today.checking')
                      : t('smartReturn.today.lead', { m: data.reminderLeadMinutes })}
                  </AppText>
                </View>
                <Button
                  label={t('smartReturn.today.cancel')}
                  variant="ghost"
                  size="sm"
                  block={false}
                  onPress={() => mutations.cancelToday.mutate()}
                />
              </View>
            </Card>
          )}

          {/* Home location */}
          <Card style={styles.sectionCard}>
            <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
              {t('smartReturn.home')}
            </AppText>
            <View style={styles.rowBetween}>
              <View style={styles.homeStatus}>
                <MaterialCommunityIcons
                  name={data.homeLatitude != null ? 'home-map-marker' : 'home-alert-outline'}
                  size={20}
                  color={data.homeLatitude != null ? colors.secondary : colors.tertiary}
                />
                <AppText variant="bodyMd">
                  {data.homeLatitude != null ? (data.homeLabel ?? t('smartReturn.homeSet')) : t('smartReturn.homeUnset')}
                </AppText>
              </View>
              <Button
                label={t('smartReturn.setHome')}
                variant="tonal"
                size="sm"
                block={false}
                onPress={() => setPickerOpen(true)}
              />
            </View>
          </Card>

          {/* Default return time */}
          <Card style={styles.sectionCard}>
            <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
              {t('smartReturn.returnTime')}
            </AppText>
            <View style={styles.timeRow}>
              <IconButton icon="minus" size={38} variant="surface" elevated accessibilityLabel="-15" onPress={() => stepTime(-15)} />
              <AppText variant="countdownLg" tabular>
                {timeLabel}
              </AppText>
              <IconButton icon="plus" size={38} variant="surface" elevated accessibilityLabel="+15" onPress={() => stepTime(15)} />
              <Button label={t('common.save')} size="sm" block={false} onPress={saveTime} />
            </View>
          </Card>

          {/* Reminder lead */}
          <Card style={styles.sectionCard}>
            <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
              {t('smartReturn.lead')}
            </AppText>
            <View style={styles.leadRow}>
              {LEAD_OPTIONS.map((minutes) => (
                <Chip
                  key={minutes}
                  label={t('smartReturn.leadValue', { m: minutes })}
                  size="sm"
                  selected={data.reminderLeadMinutes === minutes}
                  onPress={() => save({ reminderLeadMinutes: minutes }, true)}
                />
              ))}
            </View>
          </Card>

          {/* Privacy — in context, plain language. */}
          <Card tone={1} shadow={false} style={styles.privacyCard}>
            <MaterialCommunityIcons name="shield-check-outline" size={20} color={colors.primary} />
            <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.privacyText}>
              {t('smartReturn.privacy')}
            </AppText>
          </Card>
        </ScrollView>
      )}

      <HomePickerModal
        visible={pickerOpen}
        initial={
          data?.homeLatitude != null && data.homeLongitude != null
            ? { lat: data.homeLatitude, lng: data.homeLongitude }
            : null
        }
        label={homeLabel}
        onChangeLabel={setHomeLabelEdit}
        onCancel={() => setPickerOpen(false)}
        onConfirm={(center) => {
          setPickerOpen(false);
          save({
            homeLatitude: center.lat,
            homeLongitude: center.lng,
            homeLabel: homeLabel.trim() || null,
            enabled: true,
          });
        }}
      />
    </SafeAreaView>
  );
}

function HomePickerModal({
  visible,
  initial,
  label,
  onChangeLabel,
  onCancel,
  onConfirm,
}: {
  visible: boolean;
  initial: LatLng | null;
  label: string;
  onChangeLabel: (value: string) => void;
  onCancel: () => void;
  onConfirm: (center: LatLng) => void;
}) {
  const theme = useTheme();
  const t = useT();
  const device = useLocation();
  const mapRef = useRef<MapSurfaceHandle>(null);
  const [center, setCenter] = useState<LatLng | null>(initial);
  const { colors } = theme;

  const start = initial ?? device.position ?? DEFAULT_MAP_CENTER;

  return (
    <Modal
      visible={visible}
      animationType="slide"
      statusBarTranslucent
      navigationBarTranslucent
      onRequestClose={onCancel}
    >
      <SafeAreaView style={[styles.pickerSafe, { backgroundColor: colors.background }]}>
        <ScreenHeader title={t('smartReturn.setHome')} onBack={onCancel} />
        <View style={styles.pickerMapWrap}>
          <MapSurface
            ref={mapRef}
            initialCenter={start}
            initialZoom={initial ? LOCATED_ZOOM : DEFAULT_PICKER_ZOOM}
            interactiveSpots={false}
            onMoveEnd={(event) => setCenter({ lat: event.lat, lng: event.lng })}
            style={styles.pickerMap}
          />
          <View pointerEvents="none" style={styles.pickerPin}>
            <MaterialCommunityIcons name="home-map-marker" size={42} color={colors.primary} />
          </View>
          <View style={styles.pickerLocate}>
            <IconButton
              icon="crosshairs-gps"
              size={44}
              variant="surface"
              elevated
              accessibilityLabel={t('share.location.useMyLocation')}
              onPress={() => {
                void (device.status === 'granted' ? device.refresh() : device.request()).then(
                  (position) => {
                    if (position) {
                      mapRef.current?.flyTo({ ...position, zoom: LOCATED_ZOOM });
                      setCenter(position);
                    }
                  },
                );
              }}
            />
          </View>
        </View>
        <View style={styles.pickerFooter}>
          <TextField
            label={t('smartReturn.home')}
            placeholder={t('smartReturn.homeLabelPlaceholder')}
            value={label}
            onChangeText={onChangeLabel}
          />
          <Button
            label={t('common.save')}
            onPress={() => onConfirm(center ?? start)}
          />
        </View>
      </SafeAreaView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  loading: { padding: 20, gap: 12 },
  scroll: { padding: 20, paddingTop: 6, gap: 12 },
  rowCard: {},
  rowBetween: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  rowLabels: { flex: 1, gap: 3 },
  sectionCard: { gap: 12 },
  homeStatus: { flexDirection: 'row', alignItems: 'center', gap: 8, flex: 1 },
  timeRow: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  leadRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  privacyCard: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  privacyText: { flex: 1 },
  pickerSafe: { flex: 1 },
  pickerMapWrap: { flex: 1, margin: 16, borderRadius: 20, overflow: 'hidden' },
  pickerMap: { flex: 1 },
  pickerPin: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 30,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pickerLocate: { position: 'absolute', right: 12, bottom: 12 },
  pickerFooter: { padding: 16, paddingTop: 0, gap: 12 },
});
