import { zodResolver } from '@hookform/resolvers/zod';
import type { SmartReturnSettings } from '@parkio/types';
import { Button, Icon, Input, SkeletonBlock, cn } from '@parkio/ui';
import {
  SMART_RETURN_LEAD_MINUTES_MAX,
  SMART_RETURN_LEAD_MINUTES_MIN,
  smartReturnSettingsSchema,
  smartReturnTodaySchema,
  type SmartReturnSettingsFormValues,
  type SmartReturnTodayFormValues,
} from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { usersApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { PlaceSearch } from '@/components/map/PlaceSearch';
import { SettingsSectionCard } from '@/components/product/SettingsSectionCard';
import { showError, showSuccess } from '@/lib/toast';
import { type GeocodeResult } from '@/lib/geocoding';

/** Benefits surfaced on the empty state so opting in reads as a feature, not a form. */
const BENEFITS = [
  { icon: 'schedule', key: 'smartReturn.benefits.check' },
  { icon: 'notifications_active', key: 'smartReturn.benefits.headsUp' },
  { icon: 'shield', key: 'smartReturn.benefits.private' },
] as const;

export interface SmartReturnCardProps {
  /** When opened via the notification deeplink, scroll to + focus today's plan. */
  autoFocusToday?: boolean;
}

export function SmartReturnCard({ autoFocusToday = false }: SmartReturnCardProps) {
  const { t } = useTranslation('settings');
  const query = useQuery({ queryKey: ['me', 'smart-return'], queryFn: usersApi.getSmartReturn });

  return (
    <SettingsSectionCard
      title={t('smartReturn.title')}
      icon="home_pin"
      description={t('smartReturn.description')}
    >
      {query.isPending ? (
        <SmartReturnSkeleton />
      ) : query.isError ? (
        <SettingsLoadError onRetry={() => query.refetch()} retrying={query.isFetching} />
      ) : (
        <SmartReturnPanel settings={query.data} autoFocusToday={autoFocusToday} />
      )}
    </SettingsSectionCard>
  );
}

function SmartReturnPanel({
  settings,
  autoFocusToday,
}: {
  settings: SmartReturnSettings;
  autoFocusToday: boolean;
}) {
  const configured =
    settings.enabled && settings.homeLatitude !== null && settings.homeLongitude !== null;

  if (!configured) {
    return <SetupView settings={settings} />;
  }

  return (
    <div className="flex flex-col gap-lg">
      <TodayCard settings={settings} autoFocus={autoFocusToday} />
      <SettingsSection settings={settings} />
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Empty / setup state                                                        */
/* -------------------------------------------------------------------------- */

function SetupView({ settings }: { settings: SmartReturnSettings }) {
  const { t } = useTranslation('settings');
  const [setupOpen, setSetupOpen] = useState(false);

  return (
    <div className="flex flex-col gap-lg">
      <div className="flex flex-col items-center gap-md rounded-2xl bg-surface-container-low/60 px-md py-lg text-center">
        <span className="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10">
          <Icon name="home_pin" className="text-[34px] leading-none text-primary" />
        </span>
        <div>
          <h3 className="m-0 text-title-md text-on-surface">{t('smartReturn.setupHeadline')}</h3>
          <p className="m-0 mt-xs text-body-sm text-on-surface-variant">
            {t('smartReturn.setupDescription')}
          </p>
        </div>

        <ul className="m-0 flex w-full list-none flex-col gap-sm p-0 text-left">
          {BENEFITS.map((benefit) => (
            <li key={benefit.key} className="flex items-start gap-sm">
              <Icon name={benefit.icon} className="mt-[2px] text-[18px] leading-none text-primary" />
              <span className="text-body-sm text-on-surface-variant">{t(benefit.key)}</span>
            </li>
          ))}
        </ul>

        {!setupOpen ? (
          <Button type="button" className="min-h-11 w-full sm:w-auto" onClick={() => setSetupOpen(true)}>
            <Icon name="add" className="text-[18px] leading-none" />
            {t('smartReturn.enable')}
          </Button>
        ) : null}
      </div>

      {setupOpen ? (
        <div className="animate-fade-in-up">
          <SmartReturnSettingsForm settings={settings} submitLabel={t('smartReturn.turnOn')} />
        </div>
      ) : null}

      <PrivacyNote />
    </div>
  );
}

function PrivacyNote() {
  const { t } = useTranslation('settings');
  return (
    <div className="rounded-lg border border-outline-variant/40 bg-surface-container-low p-md">
      <p className="m-0 flex items-center gap-xs text-label-md font-semibold text-on-surface">
        <Icon name="shield" className="text-[16px] leading-none text-primary" />
        {t('smartReturn.privateByDesign')}
      </p>
      <p className="m-0 mt-xs text-body-sm text-on-surface-variant">
        {t('smartReturn.privacyBody')}
      </p>
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Today's plan — the centre of the feature                                   */
/* -------------------------------------------------------------------------- */

type TodayMode = 'idle' | 'pickTime';

function TodayCard({ settings, autoFocus }: { settings: SmartReturnSettings; autoFocus: boolean }) {
  const { t } = useTranslation('settings');
  const queryClient = useQueryClient();
  const sectionRef = useRef<HTMLElement>(null);
  const [mode, setMode] = useState<TodayMode>('idle');
  const [highlight, setHighlight] = useState(false);

  const active =
    settings.todayStatus === 'LEFT_BY_CAR' || settings.todayStatus === 'RETURN_CHECK_IN_PROGRESS';

  const onSettled = (next: SmartReturnSettings) => {
    queryClient.setQueryData(['me', 'smart-return'], next);
    setMode('idle');
  };

  const planMutation = useMutation({
    mutationFn: usersApi.smartReturnLeftByCar,
    onSuccess: (next) => {
      onSettled(next);
      showSuccess(t('smartReturn.planSetToast'));
    },
    onError: () => showError(t('smartReturn.planSaveError')),
  });
  const notByCar = useMutation({
    mutationFn: usersApi.smartReturnNotByCar,
    onSuccess: (next) => {
      onSettled(next);
      showSuccess(t('smartReturn.noPlanToast'));
    },
    onError: () => showError(t('smartReturn.planUpdateError')),
  });
  const cancel = useMutation({
    mutationFn: usersApi.cancelSmartReturnToday,
    onSuccess: (next) => {
      onSettled(next);
      showSuccess(t('smartReturn.cancelledToast'));
    },
    onError: () => showError(t('smartReturn.cancelError')),
  });

  const busy = planMutation.isPending || notByCar.isPending || cancel.isPending;
  const mutationError = planMutation.error ?? notByCar.error ?? cancel.error;
  const hasMutationError = planMutation.isError || notByCar.isError || cancel.isError;

  // Arriving from the notification deeplink: scroll the card into view, focus the
  // first unanswered action, and pulse a brief highlight so the eye lands here.
  useEffect(() => {
    if (!autoFocus) return;
    if (typeof sectionRef.current?.scrollIntoView === 'function') {
      sectionRef.current.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    const focusTimer = setTimeout(
      () => sectionRef.current?.querySelector<HTMLButtonElement>('button')?.focus(),
      350,
    );
    setHighlight(true);
    const highlightTimer = setTimeout(() => setHighlight(false), 2200);
    return () => {
      clearTimeout(focusTimer);
      clearTimeout(highlightTimer);
    };
  }, [autoFocus]);

  return (
    <section
      ref={sectionRef}
      aria-label={t('smartReturn.todayAria')}
      className={cn(
        'flex flex-col gap-md rounded-2xl border p-md transition-shadow duration-std',
        highlight ? 'animate-pulse-glow border-primary/60' : 'border-outline-variant/40',
      )}
    >
      <div className="flex items-center justify-between gap-sm">
        <h3 className="m-0 text-title-md text-on-surface">{t('smartReturn.today')}</h3>
        <TodayStatusBadge settings={settings} />
      </div>

      {mode === 'pickTime' ? (
        <TimePicker
          settings={settings}
          pending={planMutation.isPending}
          onCancel={active || settings.todayStatus === 'NOT_BY_CAR' ? () => setMode('idle') : undefined}
          onSave={(returnTime) => {
            const expectedReturnAt = todayAt(returnTime);
            if (!expectedReturnAt || expectedReturnAt.getTime() <= Date.now()) {
              showError(t('smartReturn.pickLaterTime'));
              return;
            }
            planMutation.mutate({ expectedReturnAt: expectedReturnAt.toISOString() });
          }}
        />
      ) : active ? (
        <ActivePlan
          settings={settings}
          busy={busy}
          onEdit={() => setMode('pickTime')}
          onCancel={() => cancel.mutate()}
        />
      ) : settings.todayStatus === 'NOT_BY_CAR' ? (
        <NotDrivingState busy={busy} onChangedMind={() => setMode('pickTime')} />
      ) : (
        <DrivingPrompt
          cancelled={settings.todayStatus === 'CANCELLED'}
          busy={busy}
          onYes={() => setMode('pickTime')}
          onNo={() => notByCar.mutate()}
        />
      )}

      {hasMutationError ? <FriendlyApiErrorMessage error={mutationError} /> : null}
    </section>
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
  const { t } = useTranslation('settings');
  return (
    <div className="flex flex-col gap-sm">
      {cancelled ? (
        <p className="m-0 text-label-sm text-on-surface-variant">
          {t('smartReturn.cancelledPrompt')}
        </p>
      ) : null}
      <p className="m-0 text-body-md text-on-surface">{t('smartReturn.drivingQuestion')}</p>
      <div className="grid grid-cols-1 gap-sm sm:grid-cols-2">
        <Button type="button" disabled={busy} className="min-h-11 w-full" onClick={onYes}>
          <Icon name="directions_car" className="text-[18px] leading-none" />
          {t('smartReturn.yesDriving')}
        </Button>
        <Button variant="secondary" type="button" disabled={busy} className="min-h-11 w-full" onClick={onNo}>
          {t('smartReturn.notByCar')}
        </Button>
      </div>
    </div>
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
  const { t } = useTranslation('settings');
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<SmartReturnTodayFormValues>({
    resolver: zodResolver(smartReturnTodaySchema),
    defaultValues: { returnTime: returnTimeValue(settings) },
  });

  const previewCheck = formatCheckFromTime(watch('returnTime'), settings.reminderLeadMinutes);

  return (
    <form onSubmit={handleSubmit((values) => onSave(values.returnTime))} className="flex flex-col gap-sm animate-fade-in-up">
      <Input
        label={t('smartReturn.expectedReturnTime')}
        type="time"
        disabled={pending}
        error={errors.returnTime?.message}
        className="min-h-11 text-title-md"
        {...register('returnTime')}
      />
      {previewCheck ? (
        <p className="m-0 flex items-center gap-xs text-label-sm text-on-surface-variant">
          <Icon name="schedule" className="text-[16px] leading-none text-primary" />
          {t('smartReturn.checkAround', { time: previewCheck })}
        </p>
      ) : null}
      <div className="flex flex-col gap-sm sm:flex-row">
        <Button type="submit" disabled={pending} className="min-h-11 w-full sm:w-auto">
          {pending ? t('smartReturn.saving') : t('smartReturn.saveTodayPlan')}
        </Button>
        {onCancel ? (
          <Button type="button" variant="ghost" disabled={pending} className="min-h-11 w-full sm:w-auto" onClick={onCancel}>
            {t('smartReturn.cancel')}
          </Button>
        ) : null}
      </div>
    </form>
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
  const { t } = useTranslation('settings');
  const returnAt = settings.todayExpectedReturnAt ? formatClock(settings.todayExpectedReturnAt) : null;
  const checkAt = settings.todayExpectedReturnAt
    ? formatCheckTime(settings.todayExpectedReturnAt, settings.reminderLeadMinutes)
    : null;

  return (
    <div className="flex flex-col gap-md animate-fade-in-up">
      <div className="rounded-xl bg-primary/5 p-md">
        <p className="m-0 flex items-center gap-xs text-label-md font-semibold text-primary">
          <Icon name="check_circle" className="text-[18px] leading-none" />
          {t('smartReturn.activeHeadline')}
        </p>
        <dl className="m-0 mt-sm grid grid-cols-2 gap-sm">
          <div>
            <dt className="m-0 text-label-sm text-on-surface-variant">{t('smartReturn.returningAround')}</dt>
            <dd className="m-0 text-title-md text-on-surface">{returnAt ?? '—'}</dd>
          </div>
          <div>
            <dt className="m-0 text-label-sm text-on-surface-variant">{t('smartReturn.wellCheckAround')}</dt>
            <dd className="m-0 text-title-md text-on-surface">{checkAt ?? '—'}</dd>
          </div>
        </dl>
      </div>
      <div className="flex flex-col gap-sm sm:flex-row">
        <Button type="button" variant="secondary" disabled={busy} className="min-h-11 w-full sm:w-auto" onClick={onEdit}>
          <Icon name="edit" className="text-[16px] leading-none" />
          {t('smartReturn.edit')}
        </Button>
        <Button type="button" variant="ghost" disabled={busy} className="min-h-11 w-full sm:w-auto" onClick={onCancel}>
          {t('smartReturn.cancel')}
        </Button>
      </div>
    </div>
  );
}

function NotDrivingState({ busy, onChangedMind }: { busy: boolean; onChangedMind: () => void }) {
  const { t } = useTranslation('settings');
  return (
    <div className="flex flex-col gap-sm animate-fade-in-up">
      <p className="m-0 flex items-center gap-xs text-body-md text-on-surface-variant">
        <Icon name="do_not_disturb_on" className="text-[18px] leading-none" />
        {t('smartReturn.noScheduled')}
      </p>
      <Button type="button" variant="ghost" disabled={busy} className="min-h-11 w-full self-start sm:w-auto" onClick={onChangedMind}>
        {t('smartReturn.drivingAfterAll')}
      </Button>
    </div>
  );
}

function TodayStatusBadge({ settings }: { settings: SmartReturnSettings }) {
  const { t } = useTranslation('settings');
  const { labelKey, tone, icon } = todayBadge(settings.todayStatus);
  return (
    <span
      className={cn(
        'inline-flex items-center gap-xs rounded-full px-sm py-xs text-label-sm font-semibold',
        tone,
      )}
    >
      <Icon name={icon} className="text-[14px] leading-none" />
      {t(labelKey)}
    </span>
  );
}

function todayBadge(status: SmartReturnSettings['todayStatus']): {
  labelKey: string;
  tone: string;
  icon: string;
} {
  switch (status) {
    case 'LEFT_BY_CAR':
      return { labelKey: 'smartReturn.status.active', tone: 'bg-primary/10 text-primary', icon: 'directions_car' };
    case 'RETURN_CHECK_IN_PROGRESS':
      return { labelKey: 'smartReturn.status.checking', tone: 'bg-primary/10 text-primary', icon: 'sync' };
    case 'NOT_BY_CAR':
      return { labelKey: 'smartReturn.status.notToday', tone: 'bg-on-surface-variant/10 text-on-surface-variant', icon: 'do_not_disturb_on' };
    case 'CANCELLED':
      return { labelKey: 'smartReturn.status.cancelled', tone: 'bg-on-surface-variant/10 text-on-surface-variant', icon: 'cancel' };
    default:
      return { labelKey: 'smartReturn.status.notSet', tone: 'bg-on-surface-variant/10 text-on-surface-variant', icon: 'help' };
  }
}

/* -------------------------------------------------------------------------- */
/* Settings — secondary, collapsed by default                                 */
/* -------------------------------------------------------------------------- */

function SettingsSection({ settings }: { settings: SmartReturnSettings }) {
  const { t } = useTranslation('settings');
  const [open, setOpen] = useState(false);

  return (
    <div className="rounded-2xl border border-outline-variant/40">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
        className="flex min-h-11 w-full items-center justify-between gap-sm rounded-2xl px-md py-sm text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        <span className="flex items-center gap-sm text-label-md font-semibold text-on-surface">
          <Icon name="tune" className="text-[18px] leading-none text-primary" />
          {t('smartReturn.settingsToggle')}
        </span>
        <Icon
          name="expand_more"
          className={cn('text-[20px] leading-none text-on-surface-variant transition-transform duration-std', open && 'rotate-180')}
        />
      </button>
      {open ? (
        <div className="border-t border-outline-variant/30 p-md animate-fade-in-up">
          <SmartReturnSettingsForm settings={settings} submitLabel={t('smartReturn.saveChanges')} allowTurnOff />
        </div>
      ) : null}
    </div>
  );
}

function SmartReturnSettingsForm({
  settings,
  submitLabel,
  allowTurnOff = false,
}: {
  settings: SmartReturnSettings;
  submitLabel: string;
  allowTurnOff?: boolean;
}) {
  const { t } = useTranslation('settings');
  const queryClient = useQueryClient();
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const mutation = useMutation({
    mutationFn: usersApi.updateSmartReturnSettings,
    onSuccess: (next) => {
      queryClient.setQueryData(['me', 'smart-return'], next);
      showSuccess(t('smartReturn.savedToast'));
    },
    onError: () => showError(t('smartReturn.saveError')),
  });

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    formState: { errors },
  } = useForm<SmartReturnSettingsFormValues>({
    resolver: zodResolver(smartReturnSettingsSchema),
    defaultValues: {
      enabled: true,
      homeLatitude: settings.homeLatitude,
      homeLongitude: settings.homeLongitude,
      homeLabel: settings.homeLabel ?? '',
      defaultReturnTime: settings.defaultReturnTime ?? '18:30',
      reminderLeadMinutes: settings.reminderLeadMinutes,
    },
  });

  const homeLabel = watch('homeLabel');
  const homeLatitude = watch('homeLatitude');
  const homeLongitude = watch('homeLongitude');
  const hasHome = homeLatitude !== null && homeLongitude !== null;

  const onSelectPlace = (place: GeocodeResult) => {
    setValue('homeLatitude', place.lat, { shouldDirty: true, shouldValidate: true });
    setValue('homeLongitude', place.lng, { shouldDirty: true, shouldValidate: true });
    setValue('homeLabel', place.secondary || place.primary, { shouldDirty: true, shouldValidate: true });
  };

  const onRemoveHome = () => {
    setValue('homeLatitude', null, { shouldDirty: true, shouldValidate: true });
    setValue('homeLongitude', null, { shouldDirty: true, shouldValidate: true });
    setValue('homeLabel', '', { shouldDirty: true });
  };

  const onSubmit = handleSubmit((values) => {
    mutation.mutate({
      enabled: true,
      homeLatitude: values.homeLatitude,
      homeLongitude: values.homeLongitude,
      homeLabel: values.homeLabel || null,
      defaultReturnTime: values.defaultReturnTime,
      reminderLeadMinutes: values.reminderLeadMinutes,
    });
  });

  const turnOff = () => mutation.mutate({ enabled: false });

  return (
    <fieldset disabled={mutation.isPending} className="m-0 flex flex-col gap-md border-0 p-0">
      <HomeLocationField
        hasHome={hasHome}
        label={homeLabel}
        error={errors.homeLatitude?.message}
        onSelect={onSelectPlace}
        onRemove={onRemoveHome}
      />

      <Input
        label={t('smartReturn.usualHomeTime')}
        type="time"
        className="min-h-11"
        error={errors.defaultReturnTime?.message}
        {...register('defaultReturnTime')}
      />

      <button
        type="button"
        aria-expanded={advancedOpen}
        onClick={() => setAdvancedOpen((value) => !value)}
        className="inline-flex min-h-11 items-center gap-xs self-start text-label-sm font-semibold text-on-surface-variant focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        <Icon name="expand_more" className={cn('text-[18px] leading-none transition-transform duration-std', advancedOpen && 'rotate-180')} />
        {t('smartReturn.advanced')}
      </button>
      {advancedOpen ? (
        <Input
          label={t('smartReturn.leadMinutes')}
          type="number"
          min={SMART_RETURN_LEAD_MINUTES_MIN}
          max={SMART_RETURN_LEAD_MINUTES_MAX}
          inputMode="numeric"
          className="min-h-11"
          error={errors.reminderLeadMinutes?.message}
          {...register('reminderLeadMinutes')}
        />
      ) : null}

      {mutation.isError ? <FriendlyApiErrorMessage error={mutation.error} /> : null}

      <div className="flex flex-col gap-sm sm:flex-row">
        <Button type="button" onClick={onSubmit} disabled={mutation.isPending} className="min-h-11 w-full sm:w-auto">
          {mutation.isPending ? t('smartReturn.saving') : submitLabel}
        </Button>
        {allowTurnOff ? (
          <Button type="button" variant="ghost" onClick={turnOff} disabled={mutation.isPending} className="min-h-11 w-full sm:w-auto">
            {t('smartReturn.turnOff')}
          </Button>
        ) : null}
      </div>
    </fieldset>
  );
}

function HomeLocationField({
  hasHome,
  label,
  error,
  onSelect,
  onRemove,
}: {
  hasHome: boolean;
  label: string;
  error?: string;
  onSelect: (place: GeocodeResult) => void;
  onRemove: () => void;
}) {
  const { t } = useTranslation('settings');
  const [editing, setEditing] = useState(!hasHome);

  // Collapse back to the saved chip whenever a home becomes set (e.g. after a pick).
  useEffect(() => {
    if (hasHome) setEditing(false);
  }, [hasHome]);

  if (hasHome && !editing) {
    return (
      <div className="flex flex-col gap-xs">
        <span className="text-label-sm font-medium text-on-surface-variant">{t('smartReturn.homeArea')}</span>
        <div className="flex items-center gap-sm rounded-xl border border-outline-variant/40 bg-surface p-sm">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary/10">
            <Icon name="home" className="text-[18px] leading-none text-primary" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="block truncate text-body-md font-medium text-on-surface">{label || t('smartReturn.savedArea')}</span>
            <span className="flex items-center gap-xs text-label-sm text-secondary">
              <Icon name="check" className="text-[14px] leading-none" />
              {t('smartReturn.saved')}
            </span>
          </span>
          <button
            type="button"
            onClick={() => setEditing(true)}
            className="flex min-h-11 items-center rounded-full px-sm text-label-sm font-semibold text-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            {t('smartReturn.change')}
          </button>
          <button
            type="button"
            aria-label={t('smartReturn.removeHomeAria')}
            onClick={onRemove}
            className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            <Icon name="close" className="text-[18px] leading-none" />
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-xs">
      <PlaceSearch
        label={t('smartReturn.homeArea')}
        placeholder={t('smartReturn.homeSearchPlaceholder')}
        onSelect={onSelect}
      />
      {error ? (
        <span role="alert" className="text-label-sm text-error">
          {error}
        </span>
      ) : null}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Loading + error states                                                     */
/* -------------------------------------------------------------------------- */

function SmartReturnSkeleton() {
  const { t } = useTranslation('settings');
  return (
    <div className="flex flex-col gap-lg" role="status" aria-label={t('smartReturn.loadingAria')}>
      <span className="sr-only">{t('smartReturn.loading')}</span>
      <div className="flex flex-col gap-md rounded-2xl border border-outline-variant/30 p-md">
        <div className="flex items-center justify-between">
          <SkeletonBlock className="h-5 w-20" rounded="full" />
          <SkeletonBlock className="h-6 w-16" rounded="full" />
        </div>
        <SkeletonBlock className="h-4 w-44" rounded="full" />
        <div className="grid grid-cols-2 gap-sm">
          <SkeletonBlock className="h-11 w-full" rounded="full" />
          <SkeletonBlock className="h-11 w-full" rounded="full" />
        </div>
      </div>
      <SkeletonBlock className="h-12 w-full" rounded="lg" />
    </div>
  );
}

function SettingsLoadError({ onRetry, retrying }: { onRetry: () => void; retrying: boolean }) {
  const { t } = useTranslation('settings');
  return (
    <div className="flex flex-col items-center gap-md rounded-2xl bg-surface-container-low/60 px-md py-lg text-center">
      <span className="flex h-14 w-14 items-center justify-center rounded-full bg-error/10">
        <Icon name="cloud_off" className="text-[28px] leading-none text-error" />
      </span>
      <div>
        <h3 className="m-0 text-title-md text-on-surface">{t('smartReturn.loadErrorTitle')}</h3>
        <p className="m-0 mt-xs text-body-sm text-on-surface-variant">{t('smartReturn.loadErrorBody')}</p>
      </div>
      <Button type="button" onClick={onRetry} disabled={retrying} className="min-h-11 w-full sm:w-auto">
        <Icon name="refresh" className="text-[18px] leading-none" />
        {retrying ? t('smartReturn.retrying') : t('smartReturn.tryAgain')}
      </Button>
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Time helpers                                                               */
/* -------------------------------------------------------------------------- */

function returnTimeValue(settings: SmartReturnSettings): string {
  if (settings.todayExpectedReturnAt) {
    return toTimeInputValue(new Date(settings.todayExpectedReturnAt));
  }
  return settings.defaultReturnTime ?? '18:30';
}

function formatClock(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatCheckTime(expectedReturnAt: string, leadMinutes: number): string {
  const checkAt = new Date(expectedReturnAt);
  checkAt.setMinutes(checkAt.getMinutes() - leadMinutes);
  return checkAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** Live preview of the check time from an HH:mm picker value (today, local). */
function formatCheckFromTime(time: string | undefined, leadMinutes: number): string | null {
  const at = time ? todayAt(time) : null;
  if (!at) return null;
  at.setMinutes(at.getMinutes() - leadMinutes);
  return at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function toTimeInputValue(date: Date): string {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

function todayAt(time: string): Date | null {
  const [hh, mm] = time.split(':').map(Number);
  if (!Number.isInteger(hh) || !Number.isInteger(mm)) return null;
  const date = new Date();
  date.setHours(hh, mm, 0, 0);
  return date;
}