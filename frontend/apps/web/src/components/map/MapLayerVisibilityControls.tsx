import { Icon, cn } from '@parkio/ui';
import { useTranslation } from 'react-i18next';

export type MapLayerVisibilityControlsProps = {
  communityVisible: boolean;
  municipalVisible: boolean;
  onCommunityVisibleChange: (visible: boolean) => void;
  onMunicipalVisibleChange: (visible: boolean) => void;
};

/**
 * Dual-inventory map layer toggles (WEB-MUNI-05).
 * Presentation-only — does not refetch nearby data.
 */
export function MapLayerVisibilityControls({
  communityVisible,
  municipalVisible,
  onCommunityVisibleChange,
  onMunicipalVisibleChange,
}: MapLayerVisibilityControlsProps) {
  const { t } = useTranslation('map');

  return (
    <div
      role="group"
      aria-label={t('layers.groupAria')}
      data-testid="map-layer-visibility-controls"
      className="flex flex-col gap-xs"
    >
      <p className="m-0 text-label-sm font-medium text-on-surface-variant">{t('layers.groupLabel')}</p>
      <div className="flex max-w-full flex-wrap gap-xs overflow-x-auto pb-xs">
        <LayerToggle
          pressed={communityVisible}
          onPressedChange={onCommunityVisibleChange}
          testId="map-layer-community"
          icon="local_parking"
          label={t('layers.community')}
        />
        <LayerToggle
          pressed={municipalVisible}
          onPressedChange={onMunicipalVisibleChange}
          testId="map-layer-municipal"
          icon="garage"
          label={t('layers.municipal')}
        />
      </div>
    </div>
  );
}

function LayerToggle({
  pressed,
  onPressedChange,
  testId,
  icon,
  label,
}: {
  pressed: boolean;
  onPressedChange: (visible: boolean) => void;
  testId: string;
  icon: 'local_parking' | 'garage';
  label: string;
}) {
  const { t } = useTranslation('map');
  const stateHint = pressed ? t('layers.visibleHint') : t('layers.hiddenHint');

  return (
    <button
      type="button"
      aria-pressed={pressed}
      aria-label={`${label}. ${stateHint}`}
      title={`${label} — ${stateHint}`}
      data-testid={testId}
      onClick={() => onPressedChange(!pressed)}
      className={cn(
        'inline-flex shrink-0 items-center gap-xs whitespace-nowrap rounded-full border px-sm py-xs text-label-sm font-medium transition-colors',
        'focus:outline-none focus-visible:ring-2 focus-visible:ring-primary',
        pressed
          ? 'border-primary bg-primary/10 text-primary'
          : 'border-outline-variant/40 bg-surface-container-lowest text-on-surface-variant hover:bg-surface-container',
      )}
    >
      <Icon name={icon} className="text-[14px] leading-none" filled={pressed} />
      {label}
    </button>
  );
}
