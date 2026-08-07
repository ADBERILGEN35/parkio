import { Icon, SoftBadge, cn } from '@parkio/ui';
import type { ParkingCandidate, RecommendationReasonCode } from '@parkio/types';
import { useTranslation } from 'react-i18next';
import { ParkHereAtFacilityButton } from '@/features/parked-car';
import {
  MAX_VISIBLE_REASONS,
  RECOMMENDATION_REASON_I18N,
} from '@/lib/recommendationPresentation';

export type RecommendationCardProps = {
  candidate: ParkingCandidate;
  rankIndex: number;
  selected: boolean;
  onSelect: (candidate: ParkingCandidate) => void;
  /** Offer municipal Park Here when authenticated and no ACTIVE session. */
  parkHereEnabled?: boolean;
};

function formatDistance(
  meters: number,
  t: (key: string, options?: Record<string, unknown>) => string,
): string {
  if (meters < 1000) {
    return t('assistant.distanceMeters', { meters: Math.round(meters) });
  }
  return t('assistant.distanceKm', { km: (meters / 1000).toFixed(1) });
}

function rankLabel(
  rankIndex: number,
  t: (key: string, options?: Record<string, unknown>) => string,
): string {
  if (rankIndex === 0) return t('assistant.rankRecommended');
  return t('assistant.rankOption', { n: rankIndex + 1 });
}

export function RecommendationCard({
  candidate,
  rankIndex,
  selected,
  onSelect,
  parkHereEnabled = false,
}: RecommendationCardProps) {
  const { t } = useTranslation('map');
  const isMunicipal = candidate.channel === 'MUNICIPAL_FACILITY';
  const availability = candidate.availability;
  const freshness = availability?.freshness;
  const reasons = (candidate.reasons ?? [])
    .map((r) => r.code)
    .filter((code): code is RecommendationReasonCode => Boolean(code))
    .slice(0, MAX_VISIBLE_REASONS);

  const channelLabel = isMunicipal
    ? t('assistant.channelMunicipal')
    : t('assistant.channelCommunity');

  let occupancyText: string | null = null;
  if (availability?.availableSpaces != null && availability.capacityTotal != null) {
    occupancyText = t('assistant.spacesAvailable', {
      available: availability.availableSpaces,
      capacity: availability.capacityTotal,
    });
  } else if (availability?.availableSpaces != null) {
    occupancyText = t('assistant.spacesAvailableOnly', {
      available: availability.availableSpaces,
    });
  } else if (availability?.communityStatus) {
    occupancyText = availability.communityStatus;
  }

  let freshnessText: string | null = null;
  if (freshness === 'LIVE') freshnessText = t('assistant.freshnessLive');
  else if (freshness === 'AGING') freshnessText = t('assistant.freshnessAging');
  else if (freshness === 'STALE') freshnessText = t('assistant.freshnessStale');
  else if (freshness === 'UNAVAILABLE') freshnessText = t('assistant.freshnessStatic');

  const showParkHere = parkHereEnabled && isMunicipal && Boolean(candidate.refId?.trim());

  return (
    <div
      data-testid="assistant-recommendation-card"
      className={cn(
        'flex w-full flex-col gap-xs rounded-2xl border px-md py-md text-left transition-colors',
        selected
          ? 'border-primary bg-primary/10 shadow-md'
          : 'border-outline-variant/40 bg-surface-container-lowest',
      )}
    >
      <button
        type="button"
        aria-pressed={selected}
        onClick={() => onSelect(candidate)}
        className="flex w-full flex-col gap-xs text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        <div className="flex items-start justify-between gap-sm">
          <div className="min-w-0 flex-1">
            <p className="m-0 text-label-sm font-semibold text-primary">
              {rankLabel(rankIndex, t)}
            </p>
            <h3 className="m-0 mt-0.5 break-words text-title-sm text-on-surface">
              {candidate.title}
            </h3>
          </div>
          <SoftBadge tone={isMunicipal ? 'primary' : 'neutral'}>{channelLabel}</SoftBadge>
        </div>

        <div className="flex flex-wrap items-center gap-xs text-label-sm text-on-surface-variant">
          <span className="inline-flex items-center gap-0.5">
            <Icon name="near_me" className="text-[14px] leading-none" />
            {formatDistance(candidate.distanceMeters, t)}
          </span>
          {candidate.sourceLabel ? (
            <span className="truncate">{candidate.sourceLabel}</span>
          ) : null}
        </div>

        {(occupancyText || freshnessText) && (
          <p className="m-0 text-label-sm text-on-surface">
            {[freshnessText, occupancyText].filter(Boolean).join(' · ')}
          </p>
        )}

        {reasons.length > 0 ? (
          <ul
            className="m-0 flex list-none flex-wrap gap-xs p-0"
            aria-label={t('assistant.reasonsAria')}
          >
            {reasons.map((code) => (
              <li key={code}>
                <span className="inline-flex rounded-full bg-surface-container px-xs py-0.5 text-label-sm text-on-surface-variant">
                  {t(RECOMMENDATION_REASON_I18N[code])}
                </span>
              </li>
            ))}
          </ul>
        ) : null}
      </button>

      {showParkHere ? (
        <ParkHereAtFacilityButton
          facilityId={candidate.refId}
          latitude={candidate.latitude}
          longitude={candidate.longitude}
          displayLabel={candidate.title}
          size="compact"
        />
      ) : null}
    </div>
  );
}
