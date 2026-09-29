import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { useQueries } from '@tanstack/react-query';
import { Icon } from '@parkio/ui';
import type {
  AssistantDestinationOrigin,
  Destination,
  FavouriteDestination,
  FavouriteParking,
  QuickActionAvailability,
  QuickActionDescriptor,
  QuickActionKind,
  RecentDestination,
} from '@parkio/types';
import {
  destinationFromFavouriteDestination,
  destinationFromRecentDestination,
  destinationFromSavedPlace,
  resolveHomePlace,
  resolveWorkPlace,
} from '@parkio/validation';
import { useTranslation } from 'react-i18next';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { useQuickActionSources } from '@/data/hooks/useQuickActionSources';
import { municipalFacilityDetailQueryOptions } from '@/data/query-options/parking';
import {
  trackQuickActionSelected,
  trackQuickActionUnavailable,
} from '@/services/spaTelemetry';

export type QuickActionsBarProps = {
  enabled: boolean;
  authenticated: boolean;
  /** Hide when a destination is already confirmed (assistant results mode). */
  visible?: boolean;
  onSelectDestination: (destination: Destination, origin: AssistantDestinationOrigin) => void;
  onOpenSearch: () => void;
  onParkedCar: () => void;
  onSelectFavouriteParking: (facilityId: string) => void;
};

type PickerKind = 'favourite_destinations' | 'favourite_parking' | 'recent_destinations' | null;

function labelKey(kind: QuickActionKind, availability: QuickActionAvailability): string {
  if (kind === 'HOME' && availability === 'UNCONFIGURED') return 'assistant.quickActions.addHome';
  if (kind === 'WORK' && availability === 'UNCONFIGURED') return 'assistant.quickActions.addWork';
  switch (kind) {
    case 'HOME':
      return 'assistant.quickActions.home';
    case 'WORK':
      return 'assistant.quickActions.work';
    case 'PARKED_CAR':
      return 'assistant.quickActions.parkedCar';
    case 'FAVOURITE_DESTINATIONS':
      return 'assistant.quickActions.favouriteDestinations';
    case 'FAVOURITE_PARKING':
      return 'assistant.quickActions.favouriteParking';
    case 'RECENT_DESTINATIONS':
      return 'assistant.quickActions.recentDestinations';
    default:
      return 'assistant.quickActions.groupLabel';
  }
}

function iconFor(kind: QuickActionKind): string {
  switch (kind) {
    case 'HOME':
      return 'home';
    case 'WORK':
      return 'work';
    case 'PARKED_CAR':
      return 'directions_car';
    case 'FAVOURITE_DESTINATIONS':
      return 'star';
    case 'FAVOURITE_PARKING':
      return 'local_parking';
    case 'RECENT_DESTINATIONS':
      return 'history';
    default:
      return 'bolt';
  }
}

function isDisabled(availability: QuickActionAvailability): boolean {
  return (
    availability === 'EMPTY' ||
    availability === 'ERROR' ||
    availability === 'LOADING' ||
    availability === 'UNAVAILABLE'
  );
}

/**
 * Compact Quick Actions row for the web Smart Parking Assistant (WP-SPA-10).
 */
export function QuickActionsBar({
  enabled,
  authenticated,
  visible = true,
  onSelectDestination,
  onOpenSearch,
  onParkedCar,
  onSelectFavouriteParking,
}: QuickActionsBarProps) {
  const { t } = useTranslation('map');
  const sdk = useParkioSdk();
  const sources = useQuickActionSources({ enabled, authenticated });
  const [picker, setPicker] = useState<PickerKind>(null);

  const parkingIds = useMemo(
    () => (picker === 'favourite_parking' ? (sources.favouriteParking.data ?? []).map((f) => f.targetId) : []),
    [picker, sources.favouriteParking.data],
  );

  const facilityQueries = useQueries({
    queries: parkingIds.map((id) => ({
      ...municipalFacilityDetailQueryOptions(sdk, id),
      enabled: picker === 'favourite_parking' && id.length > 0,
      retry: false,
    })),
  });

  const handleAction = useCallback(
    (descriptor: QuickActionDescriptor) => {
      const { kind, availability } = descriptor;
      if (kind === 'HOME') {
        if (availability === 'UNCONFIGURED') {
          trackQuickActionUnavailable(kind, availability);
          onOpenSearch();
          return;
        }
        if (availability !== 'AVAILABLE') {
          trackQuickActionUnavailable(kind, availability);
          return;
        }
        const place = resolveHomePlace(sources.snapshot.savedPlaces);
        if (!place) return;
        trackQuickActionSelected(kind, availability);
        onSelectDestination(destinationFromSavedPlace(place), 'HOME_QUICK_ACTION');
        return;
      }
      if (kind === 'WORK') {
        if (availability === 'UNCONFIGURED') {
          trackQuickActionUnavailable(kind, availability);
          onOpenSearch();
          return;
        }
        if (availability !== 'AVAILABLE') {
          trackQuickActionUnavailable(kind, availability);
          return;
        }
        const place = resolveWorkPlace(sources.snapshot.savedPlaces);
        if (!place) return;
        trackQuickActionSelected(kind, availability);
        onSelectDestination(destinationFromSavedPlace(place), 'WORK_QUICK_ACTION');
        return;
      }
      if (kind === 'PARKED_CAR') {
        if (availability !== 'AVAILABLE') {
          trackQuickActionUnavailable(kind, availability);
          return;
        }
        trackQuickActionSelected(kind, availability);
        onParkedCar();
        return;
      }
      if (kind === 'FAVOURITE_DESTINATIONS') {
        if (availability !== 'AVAILABLE') {
          trackQuickActionUnavailable(kind, availability);
          return;
        }
        trackQuickActionSelected(kind, availability);
        const items = sources.favouriteDestinations.data ?? [];
        if (items.length === 1) {
          onSelectDestination(
            destinationFromFavouriteDestination(items[0]!),
            'FAVOURITE_DESTINATION_QUICK_ACTION',
          );
          return;
        }
        setPicker('favourite_destinations');
        return;
      }
      if (kind === 'FAVOURITE_PARKING') {
        if (availability !== 'AVAILABLE') {
          trackQuickActionUnavailable(kind, availability);
          return;
        }
        trackQuickActionSelected(kind, availability);
        setPicker('favourite_parking');
        return;
      }
      if (kind === 'RECENT_DESTINATIONS') {
        if (availability !== 'AVAILABLE') {
          trackQuickActionUnavailable(kind, availability);
          return;
        }
        trackQuickActionSelected(kind, availability);
        const items = sources.recentDestinations.data ?? [];
        if (items.length === 1) {
          onSelectDestination(
            destinationFromRecentDestination(items[0]!),
            'RECENT_DESTINATION_QUICK_ACTION',
          );
          return;
        }
        setPicker('recent_destinations');
      }
    },
    [
      onOpenSearch,
      onParkedCar,
      onSelectDestination,
      sources.favouriteDestinations.data,
      sources.recentDestinations.data,
      sources.snapshot.savedPlaces,
    ],
  );

  if (!enabled || !visible) return null;

  return (
    <div className="mt-sm" data-testid="assistant-quick-actions">
      <p className="mb-xs text-label-sm font-semibold text-on-surface-variant" id="qa-heading">
        {t('assistant.quickActions.groupLabel')}
      </p>
      <div
        role="group"
        aria-labelledby="qa-heading"
        className="flex flex-wrap gap-xs"
      >
        {sources.descriptors.map((descriptor) => {
          const disabled = isDisabled(descriptor.availability);
          const label = t(labelKey(descriptor.kind, descriptor.availability));
          const a11yExtra =
            descriptor.availability === 'ERROR'
              ? t('assistant.quickActions.unavailable')
              : descriptor.availability === 'EMPTY'
                ? t('assistant.quickActions.empty')
                : '';
          return (
            <button
              key={descriptor.kind}
              type="button"
              data-testid={`qa-${descriptor.kind.toLowerCase()}`}
              disabled={disabled && descriptor.availability !== 'UNCONFIGURED'}
              aria-label={a11yExtra ? `${label}. ${a11yExtra}` : label}
              onClick={() => handleAction(descriptor)}
              className="inline-flex min-h-11 max-w-full items-center gap-xs rounded-full bg-surface-container px-md py-xs text-label-sm font-medium text-on-surface transition-colors hover:bg-surface-container-high focus:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:cursor-not-allowed disabled:opacity-45"
            >
              <Icon name={iconFor(descriptor.kind)} className="shrink-0 text-[16px] leading-none text-primary" />
              <span className="truncate">{label}</span>
            </button>
          );
        })}
      </div>

      {picker ? (
        <QuickActionPicker
          title={
            picker === 'favourite_destinations'
              ? t('assistant.quickActions.favouriteDestinations')
              : picker === 'favourite_parking'
                ? t('assistant.quickActions.favouriteParking')
                : t('assistant.quickActions.recentDestinations')
          }
          onClose={() => setPicker(null)}
        >
          {picker === 'favourite_destinations'
            ? (sources.favouriteDestinations.data ?? []).map((item: FavouriteDestination) => (
                <PickerRow
                  key={item.id}
                  title={item.label}
                  subtitle={item.subtitle}
                  onSelect={() => {
                    setPicker(null);
                    onSelectDestination(
                      destinationFromFavouriteDestination(item),
                      'FAVOURITE_DESTINATION_QUICK_ACTION',
                    );
                  }}
                />
              ))
            : null}
          {picker === 'recent_destinations'
            ? (sources.recentDestinations.data ?? []).map((item: RecentDestination) => (
                <PickerRow
                  key={item.id}
                  title={item.label}
                  subtitle={item.subtitle}
                  onSelect={() => {
                    setPicker(null);
                    onSelectDestination(
                      destinationFromRecentDestination(item),
                      'RECENT_DESTINATION_QUICK_ACTION',
                    );
                  }}
                />
              ))
            : null}
          {picker === 'favourite_parking'
            ? (sources.favouriteParking.data ?? []).map((fav: FavouriteParking, index: number) => {
                const q = facilityQueries[index];
                const name = q?.data?.displayName ?? q?.data?.operatorName;
                const unavailable = Boolean(q?.isError);
                return (
                  <PickerRow
                    key={fav.id}
                    title={name ?? t('assistant.quickActions.parkingFallback')}
                    subtitle={
                      unavailable
                        ? t('assistant.quickActions.unavailable')
                        : q?.isPending
                          ? t('assistant.quickActions.loading')
                          : q?.data?.addressText ?? null
                    }
                    disabled={unavailable || Boolean(q?.isPending)}
                    onSelect={() => {
                      if (unavailable || q?.isPending) return;
                      setPicker(null);
                      onSelectFavouriteParking(fav.targetId);
                    }}
                  />
                );
              })
            : null}
        </QuickActionPicker>
      ) : null}
    </div>
  );
}

function QuickActionPicker({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const { t } = useTranslation('map');
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={title}
      className="mt-sm rounded-2xl border border-outline-variant/40 bg-surface p-sm shadow-deep"
      data-testid="assistant-quick-action-picker"
    >
      <div className="mb-xs flex items-center justify-between gap-sm">
        <p className="text-title-sm text-on-surface">{title}</p>
        <button
          type="button"
          onClick={onClose}
          className="rounded-full px-sm py-xs text-label-sm text-on-surface-variant hover:bg-surface-container"
          aria-label={t('assistant.closeSearch')}
        >
          {t('assistant.closeSearch')}
        </button>
      </div>
      <ul className="max-h-64 space-y-xs overflow-y-auto">{children}</ul>
    </div>
  );
}

function PickerRow({
  title,
  subtitle,
  onSelect,
  disabled,
}: {
  title: string;
  subtitle?: string | null;
  onSelect: () => void;
  disabled?: boolean;
}) {
  return (
    <li>
      <button
        type="button"
        disabled={disabled}
        onClick={onSelect}
        className="flex w-full min-h-11 flex-col items-start rounded-xl px-md py-sm text-left hover:bg-surface-container disabled:opacity-45"
      >
        <span className="text-body-md text-on-surface">{title}</span>
        {subtitle ? (
          <span className="text-body-sm text-on-surface-variant">{subtitle}</span>
        ) : null}
      </button>
    </li>
  );
}
