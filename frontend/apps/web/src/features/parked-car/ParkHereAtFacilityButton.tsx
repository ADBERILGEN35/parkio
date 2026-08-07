import { Button, Icon, cn } from '@parkio/ui';
import type { ParkedCarTargetRef } from '@parkio/types';
import { municipalParkTarget } from '@parkio/validation';
import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { showError, showInfo, showSuccess } from '@/lib/toast';
import { useParkHereAtTarget } from './useParkHereAtTarget';

export interface ParkHereAtFacilityButtonProps {
  facilityId: string;
  latitude: number;
  longitude: number;
  displayLabel?: string | null;
  className?: string;
  onParked?: () => void;
  /** Visual density — preview vs recommendation card. */
  size?: 'default' | 'compact';
}

/**
 * Explicit municipal “Buraya park ettim” CTA.
 * Starts ParkingSession at facility coordinates; records RecentParking fail-open.
 */
export function ParkHereAtFacilityButton({
  facilityId,
  latitude,
  longitude,
  displayLabel,
  className,
  onParked,
  size = 'default',
}: ParkHereAtFacilityButtonProps) {
  const { t } = useTranslation('map');
  const { busy, start, reset } = useParkHereAtTarget();

  const onClick = useCallback(async () => {
    const target: ParkedCarTargetRef = municipalParkTarget(facilityId, displayLabel);
    const outcome = await start({ latitude, longitude, target });
    if (outcome.status === 'busy') return;
    if (outcome.status === 'success') {
      showSuccess(t('parkedCar.parkHere.success'));
      onParked?.();
      reset();
      return;
    }
    if (outcome.status === 'conflict') {
      showInfo(t('parkedCar.parkHere.alreadyActive'));
      reset();
      return;
    }
    showError(t('parkedCar.parkHere.failed'));
    reset();
  }, [displayLabel, facilityId, latitude, longitude, onParked, reset, start, t]);

  const label = busy ? t('parkedCar.parkHere.saving') : t('parkedCar.parkHere.cta');

  return (
    <Button
      type="button"
      variant={size === 'compact' ? 'secondary' : 'primary'}
      className={cn(size === 'compact' ? 'w-full' : 'mt-sm w-full', className)}
      disabled={busy}
      aria-busy={busy}
      aria-label={t('parkedCar.parkHere.a11y')}
      data-testid="park-here-at-facility"
      onClick={() => void onClick()}
    >
      <Icon
        name={busy ? 'progress_activity' : 'local_parking'}
        className={cn('text-[18px] leading-none', busy && 'animate-spin')}
      />
      {label}
    </Button>
  );
}
